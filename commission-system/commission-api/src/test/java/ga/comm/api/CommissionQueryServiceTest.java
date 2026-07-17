package ga.comm.api;

import ga.comm.calc.StepConfig;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.deferral.DeferralSchedulePoster;
import ga.comm.deferral.DeferralSplitStep;
import ga.comm.deferral.fixture.InMemoryDeferralScheduleStore;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.limit.LimitGateStep;
import ga.comm.limit.LimitLedgerPoster;
import ga.comm.limit.fixture.InMemoryLimitLedgerStore;
import ga.comm.rule.model.EffectivePeriod;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommissionQueryServiceTest {

    @Test
    void 계약별_통합_뷰를_합성한다() {
        CalcTestHarness harness = new CalcTestHarness();
        InMemoryLimitLedgerStore ledgers = new InMemoryLimitLedgerStore();
        InMemoryDeferralScheduleStore schedules = new InMemoryDeferralScheduleStore();
        harness.addStep(new StepConfig(new LimitGateStep(ledgers),
                EffectivePeriod.from(LocalDate.of(2026, 7, 1)), 50));
        harness.addStep(new StepConfig(new DeferralSplitStep(),
                EffectivePeriod.from(LocalDate.of(2027, 1, 1)), 55));
        harness.addListener(new LimitLedgerPoster(ledgers));
        harness.addListener(new DeferralSchedulePoster(schedules));

        PolicyNo policy = new PolicyNo("POL-2027-VIEW");
        harness.calculator().process(EventFixtures.newContract(policy,
                LocalDate.of(2027, 3, 1), Money.won(300_000), Map.of()));

        CommissionQueryService service = new CommissionQueryService(
                harness.calcStore, ledgers, schedules);
        CommissionQueryService.PolicyCommissionView view =
                service.policyView(policy, EventFixtures.AGENT_A);

        // 분급 즉시분 756,000이 계산 레코드로, 이연 3건이 스케줄로
        assertThat(view.netAmount()).isEqualTo(Money.won(756_000));
        assertThat(view.deferralSchedules()).hasSize(3);
        // 한도 원장: 분할 전 총액 1,890,000 소진
        assertThat(view.limit()).hasValueSatisfying(l -> {
            assertThat(l.limitAmount()).isEqualTo(Money.won(3_600_000));
            assertThat(l.accumPaid()).isEqualTo(Money.won(1_890_000));
        });
    }
}
