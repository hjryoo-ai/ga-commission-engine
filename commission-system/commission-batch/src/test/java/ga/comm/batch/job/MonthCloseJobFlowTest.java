package ga.comm.batch.job;

import ga.comm.batch.BatchRequestRunner;
import ga.comm.batch.BatchTestSupport;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.domain.time.CloseYm;
import ga.comm.limit.fixture.InMemoryLimitLedgerStore;
import ga.comm.settlement.CloseReport;
import ga.comm.settlement.MonthCloseService;
import ga.comm.settlement.SettleCloseStore;
import ga.comm.settlement.fixture.InMemorySettleCloseStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 월마감 잡 흐름 (설계서 §7 ①, §10 Phase 11): PENDING 게이트 차단 → 강제 마감 재시작(사유가
 * 잡 실행 컨텍스트에 영속) → dedup. 리포트는 비차단으로 발견 항목을 컨텍스트에 남긴다.
 */
class MonthCloseJobFlowTest {

    private final BatchTestSupport.Env env = BatchTestSupport.env();
    private final CalcTestHarness harness = new CalcTestHarness();
    private final InMemorySettleCloseStore closeStore = new InMemorySettleCloseStore();

    private Job closeJob(List<CloseReport> reports) {
        MonthCloseService closeService = new MonthCloseService(harness.calcStore,
                harness.eventStore, new InMemoryLimitLedgerStore(), closeStore, List.of());
        return MonthCloseJobFactory.job(env.runtime().jobRepository(),
                new ResourcelessTransactionManager(), closeService, reports);
    }

    @Test
    @DisplayName("PENDING 게이트가 잡을 차단하고, 강제 마감 재시작은 사유를 영속 기록한다")
    void 게이트_차단과_강제_마감_재시작() {
        harness.calculator().process(EventFixtures.newContract());
        harness.eventStore.upsertByKey(EventFixtures.payment(2, LocalDate.of(2026, 8, 20)));

        CloseReport stubReport = new CloseReport() {
            @Override
            public String name() {
                return "룰 데이터 완결성";
            }

            @Override
            public List<String> findings(CloseYm closeYm) {
                return List.of("COMM_RATE 겹침 의심: rate 7/9");
            }
        };
        Job job = closeJob(List.of(stubReport));

        // ① 게이트 차단 — 잡 FAILED, 월은 OPEN 유지
        BatchRequestRunner.Outcome blocked = env.runner().submit(job,
                BatchTestSupport.closeParams("202608", "정산담당", false, null));
        assertThat(blocked.disposition()).isEqualTo(BatchRequestRunner.Disposition.FAILED);
        assertThat(closeStore.stateOf(CloseYm.of("202608")))
                .isEqualTo(SettleCloseStore.CloseState.OPEN);

        // ② 같은 인스턴스를 강제 마감으로 재시작 — 사유가 BATCH_JOB_EXECUTION_CONTEXT에 남는다
        BatchRequestRunner.Outcome forced = env.runner().submit(job,
                BatchTestSupport.closeParams("202608", "팀장", true, "보험사 지연분 익월 반영 승인"));
        assertThat(forced.disposition()).isEqualTo(BatchRequestRunner.Disposition.COMPLETED);
        assertThat(closeStore.stateOf(CloseYm.of("202608")))
                .isEqualTo(SettleCloseStore.CloseState.CLOSED);
        String checklist = forced.execution().getExecutionContext()
                .getString(MonthCloseJobFactory.CTX_CHECKLIST);
        assertThat(checklist).contains("강제 마감").contains("팀장").contains("보험사 지연분 익월 반영 승인");

        // ③ 리포트 발견 항목이 컨텍스트에 영속된다 (비차단 — 잡은 COMPLETED)
        assertThat(forced.execution().getExecutionContext()
                .getString(MonthCloseJobFactory.CTX_REPORT_PREFIX + "룰 데이터 완결성"))
                .contains("COMM_RATE 겹침 의심");
        assertThat(forced.execution().getExecutionContext()
                .getInt(MonthCloseJobFactory.CTX_REPORT_TOTAL)).isEqualTo(1);

        // ④ 완료 인스턴스 재제출은 dedup no-op
        BatchRequestRunner.Outcome dedup = env.runner().submit(job,
                BatchTestSupport.closeParams("202608", "정산담당", false, null));
        assertThat(dedup.disposition()).isEqualTo(BatchRequestRunner.Disposition.DEDUP_NOOP);
    }

    @Test
    @DisplayName("사유 없는 강제 마감은 잡이 실패한다")
    void 강제_마감_사유_필수() {
        harness.calculator().process(EventFixtures.newContract());
        harness.eventStore.upsertByKey(EventFixtures.payment(2, LocalDate.of(2026, 8, 20)));
        Job job = closeJob(List.of());

        BatchRequestRunner.Outcome outcome = env.runner().submit(job,
                BatchTestSupport.closeParams("202609", "팀장", true, null));
        assertThat(outcome.disposition()).isEqualTo(BatchRequestRunner.Disposition.FAILED);
    }
}
