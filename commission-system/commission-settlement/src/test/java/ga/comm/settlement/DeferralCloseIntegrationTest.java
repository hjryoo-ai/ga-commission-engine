package ga.comm.settlement;

import ga.comm.calc.StepConfig;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.clawback.ClawbackOffsetService;
import ga.comm.clawback.fixture.InMemoryClawbackReceivableStore;
import ga.comm.deferral.DeferralReleaseService;
import ga.comm.deferral.DeferralSchedulePoster;
import ga.comm.deferral.DeferralSplitStep;
import ga.comm.deferral.fixture.InMemoryDeferralScheduleStore;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;
import ga.comm.limit.fixture.InMemoryLimitLedgerStore;
import ga.comm.rule.model.EffectivePeriod;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 통합: 마감 ② 분급 도래분 RELEASE → 당월 편입 → ⑤ 확정 (설계서 §7). */
class DeferralCloseIntegrationTest {

    @Test
    void 마감이_분급_도래분을_지급_파이프라인에_투입하고_확정한다() {
        CalcTestHarness harness = new CalcTestHarness();
        InMemoryDeferralScheduleStore schedules = new InMemoryDeferralScheduleStore();
        harness.addStep(new StepConfig(new DeferralSplitStep(),
                EffectivePeriod.from(LocalDate.of(2027, 1, 1)), 55));
        harness.addListener(new DeferralSchedulePoster(schedules));

        DeferralReleaseService releaseService = new DeferralReleaseService(
                schedules, harness.calcStore, (policy, asOf) -> true);

        var closeStore = new ga.comm.settlement.fixture.InMemorySettleCloseStore();
        var settlementStore = new ga.comm.settlement.fixture.InMemoryAgentSettlementStore();
        MonthCloseService closeService = new MonthCloseService(
                harness.calcStore, harness.eventStore, new InMemoryLimitLedgerStore(),
                closeStore, List.of(new DeferralReleaseCloseHook(releaseService)));
        PayoutService payoutService = new PayoutService(harness.calcStore, settlementStore,
                closeStore, new ClawbackOffsetService(new InMemoryClawbackReceivableStore()),
                WithholdingTaxPolicy.STANDARD_3_3);

        // 2027-06 체결 → 12개월 후(202806) 378,000 도래
        harness.calculator().process(EventFixtures.newContract(new PolicyNo("POL-2027-CLOSE"),
                LocalDate.of(2027, 6, 1), Money.won(300_000), Map.of()));

        MonthCloseService.CloseResult result = closeService.close(CloseYm.of("202806"), "정산담당");

        assertThat(result.closed()).isTrue();
        assertThat(result.checklist()).anySatisfy(c -> {
            assertThat(c.name()).isEqualTo("분급 도래분 RELEASE");
            assertThat(c.detail()).contains("지급 투입 1건");
        });

        // 도래분이 당월 정산에 편입되고 CONFIRMED로 확정된다
        var deferredRecords = harness.calcStore.findByCloseYm(CloseYm.of("202806")).stream()
                .filter(r -> r.commType().equals(CommTypeCode.DEFERRED))
                .toList();
        assertThat(deferredRecords).hasSize(1);
        assertThat(deferredRecords.get(0).calcAmount()).isEqualTo(Money.won(378_000));
        assertThat(deferredRecords.get(0).status()).isEqualTo(CalcStatus.CONFIRMED);

        // 지급 런에서 도래분이 정산된다 (§7 D+1~)
        PayoutService.RunResult run = payoutService.run(CloseYm.of("202806"), 1);
        assertThat(run.settlements()).anySatisfy(row ->
                assertThat(row.payable()).isEqualTo(Money.won(378_000)));
    }
}
