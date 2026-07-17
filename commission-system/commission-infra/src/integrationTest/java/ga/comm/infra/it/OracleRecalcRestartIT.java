package ga.comm.infra.it;

import ga.comm.batch.BatchRequestRunner;
import ga.comm.batch.job.RecalcJobFactory;
import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.calc.net.NetAmountCalculator;
import ga.comm.calc.revision.RevisionService;
import ga.comm.calc.store.CloseStatusProvider;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.domain.type.CalcStatus;
import ga.comm.domain.type.Direction;
import ga.comm.infra.OraclePersistence;
import ga.comm.limit.LimitLedger;
import ga.comm.limit.LimitReversalHook;
import ga.comm.rule.admin.RateApprovalService;
import ga.comm.rule.fixture.InMemoryRuleStore;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.rule.model.EffectivePeriod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재계산 잡 중단·재시작 (실 Oracle, Phase 11 완료 기준): 임의 지점 중단 후 재시작해도
 * 수급자×유형 순액이 동일하고(값 멱등성, §6.4), 중단된 항목의 [3.5]~[5.5]는 통째로
 * 롤백되어 부분 커밋이 없다(항목 단위 트랜잭션, §6.1.6).
 */
class OracleRecalcRestartIT {

    /** kill 시뮬레이션 — 업무 예외(격리 대상)와 달리 Error는 전체 중단으로 취급된다. */
    static final class KillSwitchError extends Error {
        KillSwitchError() {
            super("프로세스 중단 시뮬레이션");
        }
    }

    private static final PolicyNo KILL_AT = new PolicyNo("POL-R3");

    private final OraclePersistence persistence = OracleTestSupport.persistence();

    @BeforeEach
    void clean() {
        OracleTestSupport.cleanAll();
    }

    private Money agentFyNet(PolicyNo policy) {
        return NetAmountCalculator.netOf(persistence.inTx(() -> persistence.commCalcStore()
                .findByPolicyAndRecipient(policy, EventFixtures.AGENT_A.value())).stream()
                .filter(r -> r.commType().equals(CommTypeCode.FY_COMM))
                .toList());
    }

    private LimitLedger ledgerOf(PolicyNo policy) {
        return persistence.inTx(() -> persistence.limitLedgerStore()
                .find(policy, EventFixtures.AGENT_A)).orElseThrow();
    }

    @Test
    void 중단_재시작_값_멱등성과_항목_단위_트랜잭션_원자성() {
        InMemoryRuleStore rules = RuleFixtures.standardRules();
        CommissionCalculator calculator = OracleBatchSupport.calculator(persistence, rules);

        // 4건 신계약 (각 설계사 FY 1,890,000 + 오버라이드 3건 = 이벤트당 4레코드)
        List<PolicyNo> policies = List.of(new PolicyNo("POL-R1"), new PolicyNo("POL-R2"),
                KILL_AT, new PolicyNo("POL-R4"));
        List<Long> eventIds = new ArrayList<>();
        for (int i = 0; i < policies.size(); i++) {
            PolicyNo policy = policies.get(i);
            LocalDate date = LocalDate.of(2026, 8, 1 + i);
            List<CommCalcRecord> records = persistence.inTx(() -> calculator.process(
                    EventFixtures.newContract(policy, date, Money.won(300_000), Map.of())));
            eventIds.add(records.get(0).eventId());
        }

        // 소급 요율 7.0 → 6.5 (룰은 읽기 전용 인메모리 픽스처 — 승인 워크플로는 Phase 10b에서 검증됨)
        RateApprovalService approval = new RateApprovalService(rules);
        approval.approve(approval.registerDraft(Direction.OUTBOUND, RuleFixtures.INSURER,
                RuleFixtures.PRODUCT, CommTypeCode.FY_COMM, null, Rate.of("6.5"),
                EffectivePeriod.from(LocalDate.of(2026, 8, 1))).rateId());

        // 3번째 이벤트(POL-R3) 처리 도중 kill — reversal 훅에서 Error 발생 (1회만)
        AtomicBoolean armed = new AtomicBoolean(true);
        RevisionService revision = new RevisionService(persistence.commCalcStore(),
                CloseStatusProvider.noneClosed(), List.of(
                new LimitReversalHook(persistence.limitLedgerStore()),
                (original, reversal) -> {
                    if (original.policyNo().equals(KILL_AT) && armed.get()) {
                        armed.set(false);
                        throw new KillSwitchError();
                    }
                }));

        Job job = RecalcJobFactory.job(OracleBatchSupport.runtime().jobRepository(),
                persistence.txTemplate(), revision, persistence.policyEventStore(), calculator);
        String csv = String.join(",", eventIds.stream().map(String::valueOf).toList());
        var params = OracleBatchSupport.recalcParams("REQ-RETRO-1", csv, "소급 요율 7.0→6.5");

        // ① 1차 실행: 이벤트 1·2 커밋 후 이벤트 3 도중 중단 → 잡 FAILED
        BatchRequestRunner.Outcome killed = OracleBatchSupport.runner().submit(job, params);
        assertThat(killed.disposition()).isEqualTo(BatchRequestRunner.Disposition.FAILED);

        // 이벤트 1·2: 재계산 완료 (원본 4 + reversal 4 + rebook 4)
        for (PolicyNo done : List.of(policies.get(0), policies.get(1))) {
            assertThat(agentFyNet(done)).isEqualTo(Money.won(1_755_000));
            assertThat(ledgerOf(done).accumPaid()).isEqualTo(Money.won(1_755_000));
        }
        // 이벤트 3: 항목 트랜잭션 통째 롤백 — 부분 reversal 없음, 원장 그대로 (§6.1.6 원자성)
        List<CommCalcRecord> r3Records = persistence.inTx(() -> persistence.commCalcStore()
                .findByPolicyAndRecipient(KILL_AT, EventFixtures.AGENT_A.value()));
        assertThat(r3Records).hasSize(1);
        assertThat(r3Records.get(0).status()).isEqualTo(CalcStatus.CALCULATED);
        assertThat(agentFyNet(KILL_AT)).isEqualTo(Money.won(1_890_000));
        assertThat(ledgerOf(KILL_AT).accumPaid()).isEqualTo(Money.won(1_890_000));
        // 이벤트 4: 미처리
        assertThat(agentFyNet(policies.get(3))).isEqualTo(Money.won(1_890_000));

        // ② 같은 request_id 재제출 = 재시작: 진행 오프셋부터 계속 — 이벤트 1·2는 재처리되지 않는다
        BatchRequestRunner.Outcome restarted = OracleBatchSupport.runner().submit(job, params);
        assertThat(restarted.disposition()).isEqualTo(BatchRequestRunner.Disposition.COMPLETED);
        assertThat(restarted.execution().getStepExecutions().iterator().next()
                .getExecutionContext().getInt(RecalcJobFactory.CTX_DONE, 0)).isEqualTo(4);

        for (PolicyNo policy : policies) {
            // 값 멱등성: 중단·재시작을 거쳐도 순액은 무중단 기준값과 동일
            assertThat(agentFyNet(policy)).isEqualTo(Money.won(1_755_000));
            // 세대도 정확히 1회분: 수급자 4 × (원본+reversal+rebook) = 12
            assertThat(persistence.inTx(() -> persistence.commCalcStore()
                    .findByEventId(eventIds.get(policies.indexOf(policy))))).hasSize(12);
            LimitLedger ledger = ledgerOf(policy);
            assertThat(ledger.accumPaid()).isEqualTo(Money.won(1_755_000));
            assertThat(ledger.invariantHolds()).isTrue();
        }

        // ③ 완료 후 같은 요청 재제출 = no-op (§6.4 배치 계층 멱등) — 레코드 증식 없음
        assertThat(OracleBatchSupport.runner().submit(job, params).disposition())
                .isEqualTo(BatchRequestRunner.Disposition.DEDUP_NOOP);
        for (Long eventId : eventIds) {
            assertThat(persistence.inTx(() -> persistence.commCalcStore()
                    .findByEventId(eventId))).hasSize(12);
        }
    }
}
