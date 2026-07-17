package ga.comm.batch.job;

import ga.comm.batch.BatchRequestRunner;
import ga.comm.batch.BatchTestSupport;
import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.calc.net.NetAmountCalculator;
import ga.comm.calc.revision.RevisionService;
import ga.comm.calc.store.CloseStatusProvider;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.Direction;
import ga.comm.domain.type.RecipientType;
import ga.comm.rule.admin.RateApprovalService;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.rule.model.EffectivePeriod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재계산 잡 흐름 (설계서 §6.4): 요청 ID dedup, 실패 이벤트 항목 격리, 값 멱등.
 * 항목 단위 트랜잭션·중단 후 재시작은 실 Oracle IT에서 증명한다.
 */
class RecalcJobFlowTest {

    private final BatchTestSupport.Env env = BatchTestSupport.env();
    private final CalcTestHarness harness = new CalcTestHarness();

    private Money agentFyNet() {
        return new NetAmountCalculator(harness.calcStore)
                .netOf(RecipientType.AGENT, EventFixtures.AGENT_A.value(),
                        CommTypeCode.FY_COMM, CloseYm.of("202608"));
    }

    @Test
    @DisplayName("소급 요율 재계산: 실패 이벤트는 격리, 나머지는 수렴 — 같은 요청 재제출은 no-op")
    void 재계산_격리와_dedup과_값_멱등() {
        CommissionCalculator calculator = harness.calculator();
        List<CommCalcRecord> first = calculator.process(EventFixtures.newContract());
        List<CommCalcRecord> second = calculator.process(EventFixtures.newContract(
                new PolicyNo("POL-2026-0002"), LocalDate.of(2026, 8, 5), Money.won(300_000), Map.of()));
        long event1 = first.get(0).eventId();
        long event2 = second.get(0).eventId();

        RateApprovalService approval = new RateApprovalService(harness.rules);
        approval.approve(approval.registerDraft(Direction.OUTBOUND, RuleFixtures.INSURER,
                RuleFixtures.PRODUCT, CommTypeCode.FY_COMM, null, Rate.of("6.5"),
                EffectivePeriod.from(LocalDate.of(2026, 8, 1))).rateId());

        RevisionService revision = new RevisionService(harness.calcStore,
                CloseStatusProvider.noneClosed(), List.of());
        Job job = RecalcJobFactory.job(env.runtime().jobRepository(),
                new TransactionTemplate(new ResourcelessTransactionManager()),
                revision, harness.eventStore, calculator);

        // 존재하지 않는 이벤트 999999는 격리되고 나머지 2건은 재계산된다
        BatchRequestRunner.Outcome outcome = env.runner().submit(job,
                BatchTestSupport.recalcParams("REQ-2026-081", event1 + "," + event2 + ",999999",
                        "소급 요율 7.0→6.5"));
        assertThat(outcome.disposition()).isEqualTo(BatchRequestRunner.Disposition.COMPLETED);
        assertThat(outcome.execution().getExecutionContext()
                .getString(RecalcJobFactory.CTX_ISOLATED)).contains("999999");

        // 순액 = 새 요율 기준 2건 합 (300,000×6.5×0.9 = 1,755,000)
        assertThat(agentFyNet()).isEqualTo(Money.won(2 * 1_755_000));
        int recordsAfterFirst = harness.calcStore.all().size();

        // 같은 요청 ID 재제출 → no-op (레코드 수 그대로)
        assertThat(env.runner().submit(job, BatchTestSupport.recalcParams("REQ-2026-081",
                event1 + "," + event2 + ",999999", "소급 요율 7.0→6.5")).disposition())
                .isEqualTo(BatchRequestRunner.Disposition.DEDUP_NOOP);
        assertThat(harness.calcStore.all()).hasSize(recordsAfterFirst);

        // 다른 요청 ID로 같은 재계산을 다시 돌려도 순액은 동일 (값 멱등, §6.4)
        assertThat(env.runner().submit(job, BatchTestSupport.recalcParams("REQ-2026-082",
                event1 + "," + event2, "재검증")).disposition())
                .isEqualTo(BatchRequestRunner.Disposition.COMPLETED);
        assertThat(agentFyNet()).isEqualTo(Money.won(2 * 1_755_000));
    }
}
