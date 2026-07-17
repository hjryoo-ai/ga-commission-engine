package ga.comm.infra.it;

import ga.comm.batch.BatchRequestRunner;
import ga.comm.batch.job.DeferralReleaseJobFactory;
import ga.comm.batch.job.MonthCloseJobFactory;
import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.StepConfig;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.deferral.DeferralReleaseService;
import ga.comm.deferral.DeferralSchedulePoster;
import ga.comm.deferral.DeferralSplitStep;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;
import ga.comm.domain.type.Direction;
import ga.comm.domain.type.RecipientType;
import ga.comm.infra.OraclePersistence;
import ga.comm.rule.admin.RateApprovalService;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.settlement.MonthCloseService;
import ga.comm.settlement.SettleCloseStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;

import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 월마감 잡 (실 Oracle, Phase 11): PENDING 게이트 차단 → 강제 마감 재시작(사유가
 * BATCH_JOB_EXECUTION_CONTEXT에 영속) → dedup, 그리고 마감 리포트 3종의 실 검출.
 */
class OracleMonthCloseJobIT {

    private final OraclePersistence persistence = OracleTestSupport.persistence();

    @BeforeEach
    void clean() {
        OracleTestSupport.cleanAll();
    }

    private Job closeJob() {
        MonthCloseService closeService = new MonthCloseService(persistence.commCalcStore(),
                persistence.policyEventStore(), persistence.limitLedgerStore(),
                persistence.settleCloseStore(), List.of());
        return MonthCloseJobFactory.job(OracleBatchSupport.runtime().jobRepository(),
                persistence.txManager(), closeService, persistence.closeReports());
    }

    @Test
    void 게이트_차단_강제_마감_리포트_3종_검출_그리고_dedup() throws Exception {
        CommissionCalculator calculator =
                OracleBatchSupport.calculator(persistence, RuleFixtures.standardRules());
        persistence.inTx(() -> calculator.process(EventFixtures.newContract()));
        // 수신만 되고 처리되지 않은 이벤트 → 게이트 차단 대상
        persistence.inTx(() -> persistence.policyEventStore()
                .upsertByKey(EventFixtures.payment(2, LocalDate.of(2026, 8, 20))));

        // 리포트 ① 씨앗: 같은 키에 유효기간이 겹치는 ACTIVE 요율 2건 (수기 등록 시나리오 — 앱 계층 우회)
        seedOverlappingActiveRates();
        // 리포트 ② 씨앗: 연 파티션(p2028) 밖의 close_ym → pmax 적재
        seedPmaxRow();
        // 리포트 ③ 씨앗: 같은 rate에 ACTIVATE 직후 SUPERSEDE (근접 동시 승인 흔적)
        seedApprovalContention();

        Job job = closeJob();

        // ① 게이트 차단 — 잡 FAILED, 월은 OPEN 유지
        BatchRequestRunner.Outcome blocked = OracleBatchSupport.runner().submit(job,
                OracleBatchSupport.closeParams("202608", "정산담당", false, null));
        assertThat(blocked.disposition()).isEqualTo(BatchRequestRunner.Disposition.FAILED);
        assertThat(persistence.inTx(() -> persistence.settleCloseStore()
                .stateOf(CloseYm.of("202608")))).isEqualTo(SettleCloseStore.CloseState.OPEN);

        // ② 강제 마감 재시작 — 사유 영속 + 확정 + CLOSED
        BatchRequestRunner.Outcome forced = OracleBatchSupport.runner().submit(job,
                OracleBatchSupport.closeParams("202608", "팀장", true, "보험사 지연분 익월 반영 승인"));
        assertThat(forced.disposition()).isEqualTo(BatchRequestRunner.Disposition.COMPLETED);
        assertThat(persistence.inTx(() -> persistence.settleCloseStore()
                .stateOf(CloseYm.of("202608")))).isEqualTo(SettleCloseStore.CloseState.CLOSED);
        assertThat(persistence.inTx(() -> persistence.commCalcStore()
                .findByCloseYm(CloseYm.of("202608"))))
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(CalcStatus.CONFIRMED));

        String checklist = forced.execution().getExecutionContext()
                .getString(MonthCloseJobFactory.CTX_CHECKLIST);
        assertThat(checklist).contains("강제 마감").contains("팀장")
                .contains("보험사 지연분 익월 반영 승인");

        // 사유는 배치 컨텍스트뿐 아니라 업무 테이블 SETTLE_CLOSE.force_reason에 정본으로 영속된다
        // (v1.1.5 §7 — 배치 메타는 보존기간·정리 대상이므로 업무 테이블이 정본)
        assertThat(persistence.inTx(() -> persistence.settleCloseStore()
                .forceReasonOf(CloseYm.of("202608")))).isEqualTo("보험사 지연분 익월 반영 승인");

        // ③ 리포트 3종 실 검출 (비차단 — 잡은 COMPLETED)
        var jobCtx = forced.execution().getExecutionContext();
        assertThat(jobCtx.getString(MonthCloseJobFactory.CTX_REPORT_PREFIX + "룰 데이터 완결성"))
                .contains("COMM_RATE 겹치는 ACTIVE: rate 900001/900002")
                .contains("COMM_TYPE_MST 누락: FY_COMM");
        assertThat(jobCtx.getString(MonthCloseJobFactory.CTX_REPORT_PREFIX + "MAXVALUE 파티션 적재"))
                .contains("close_ym 202901");
        assertThat(jobCtx.getString(MonthCloseJobFactory.CTX_REPORT_PREFIX + "승인 경합 감지"))
                .contains("ACTIVATE(승인자A)").contains("SUPERSEDE(승인자B)");
        assertThat(jobCtx.getInt(MonthCloseJobFactory.CTX_REPORT_TOTAL)).isGreaterThanOrEqualTo(4);

        // 기록이 실제로 Oracle에 영속됐다 (BATCH_JOB_EXECUTION_CONTEXT)
        try (Connection c = OracleTestSupport.dataSource().getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM BATCH_JOB_EXECUTION_CONTEXT")) {
            rs.next();
            assertThat(rs.getLong(1)).isGreaterThanOrEqualTo(2);
        }

        // ④ 완료 인스턴스 재제출 = no-op
        assertThat(OracleBatchSupport.runner().submit(job,
                OracleBatchSupport.closeParams("202608", "정산담당", false, null)).disposition())
                .isEqualTo(BatchRequestRunner.Disposition.DEDUP_NOOP);
    }

    @Test
    void 분급_도래_잡은_도래분을_투입하고_재제출은_noop이다() {
        CommissionCalculator calculator = OracleBatchSupport.calculator(persistence,
                RuleFixtures.standardRules(),
                List.of(new StepConfig(new DeferralSplitStep(),
                        EffectivePeriod.from(LocalDate.of(2027, 1, 1)), 55)),
                new DeferralSchedulePoster(persistence.deferralScheduleStore()));
        persistence.inTx(() -> calculator.process(EventFixtures.newContract(
                new PolicyNo("POL-2027-REL"), LocalDate.of(2027, 6, 1), Money.won(300_000), Map.of())));

        DeferralReleaseService releaseService = new DeferralReleaseService(
                persistence.deferralScheduleStore(), persistence.commCalcStore(),
                (policy, asOf) -> true);
        Job job = DeferralReleaseJobFactory.job(OracleBatchSupport.runtime().jobRepository(),
                persistence.txManager(), releaseService);

        BatchRequestRunner.Outcome outcome = OracleBatchSupport.runner().submit(job,
                OracleBatchSupport.releaseParams("202806"));
        assertThat(outcome.disposition()).isEqualTo(BatchRequestRunner.Disposition.COMPLETED);
        assertThat(outcome.execution().getExecutionContext()
                .getString(DeferralReleaseJobFactory.CTX_SUMMARY)).contains("지급 투입 1건");

        List<CommCalcRecord> deferred = persistence.inTx(() -> persistence.commCalcStore()
                .findByCloseYm(CloseYm.of("202806"))).stream()
                .filter(r -> r.commType().equals(CommTypeCode.DEFERRED)).toList();
        assertThat(deferred).hasSize(1);
        assertThat(deferred.get(0).calcAmount()).isEqualTo(Money.won(378_000));

        // 재제출 no-op — 도래분이 이중 투입되지 않는다
        assertThat(OracleBatchSupport.runner().submit(job,
                OracleBatchSupport.releaseParams("202806")).disposition())
                .isEqualTo(BatchRequestRunner.Disposition.DEDUP_NOOP);
        assertThat(persistence.inTx(() -> persistence.commCalcStore()
                .findByCloseYm(CloseYm.of("202806"))).stream()
                .filter(r -> r.commType().equals(CommTypeCode.DEFERRED))).hasSize(1);
    }

    private void seedOverlappingActiveRates() {
        for (long[] seed : List.of(new long[]{900001, 20260101}, new long[]{900002, 20260601})) {
            JdbcRuleSeeder.execute(OracleTestSupport.dataSource(), """
                    INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type,
                        installment_no, rate, apply_from, version_no, status)
                    VALUES (?, 'OUTBOUND', 'SAMLIFE', 'RPT-OVERLAP', 'INCENTIVE', NULL, 1, ?, 1, 'ACTIVE')
                    """, ps -> {
                ps.setLong(1, seed[0]);
                String ymd = String.valueOf(seed[1]);
                ps.setDate(2, Date.valueOf(LocalDate.of(Integer.parseInt(ymd.substring(0, 4)),
                        Integer.parseInt(ymd.substring(4, 6)), Integer.parseInt(ymd.substring(6)))));
            });
        }
    }

    private void seedPmaxRow() {
        long eventId = OracleTestSupport.newEventId();
        persistence.inTx(() -> {
            persistence.policyEventStore().markProcessed(eventId);
            persistence.commCalcStore().insert(new CommCalcRecord(null, eventId,
                    new PolicyNo("POL-PMAX"), RecipientType.AGENT, "A-1001", CommTypeCode.FY_COMM,
                    Money.won(100_000), Rate.of("1"), Money.won(100_000), Money.ZERO,
                    CloseYm.of("202901"), CalcStatus.CALCULATED, null, "[]", "[]"));
        });
    }

    private void seedApprovalContention() {
        RateApprovalService approval = new RateApprovalService(persistence.commRateAdminStore());
        long firstRate = persistence.inTx(() -> approval.approve(
                approval.registerDraft(Direction.OUTBOUND, new InsurerCode("SAMLIFE"),
                        new ProductKey("RPT-CONTENTION"), CommTypeCode.RENEWAL, null,
                        Rate.of("5.0"), EffectivePeriod.from(LocalDate.of(2026, 10, 1))).rateId(),
                "승인자A")).rateId();
        persistence.inTx(() -> approval.approve(
                approval.registerDraft(Direction.OUTBOUND, new InsurerCode("SAMLIFE"),
                        new ProductKey("RPT-CONTENTION"), CommTypeCode.RENEWAL, null,
                        Rate.of("5.5"), EffectivePeriod.from(LocalDate.of(2026, 10, 1))).rateId(),
                "승인자B"));
        assertThat(firstRate).isPositive();
    }
}
