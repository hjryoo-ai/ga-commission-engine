package ga.comm.deferral;

import ga.comm.calc.StepConfig;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.deferral.fixture.InMemoryDeferralScheduleStore;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.type.RecipientType;
import ga.comm.rule.model.EffectivePeriod;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 불변식 (설계서 §8.2): 임의 금액에서 분급 스케줄 합 = 이연 원금. */
class DeferralSplitPropertyTest {

    @Property(tries = 100)
    void 분급_스케줄_합은_항상_이연_원금과_같다(
            @ForAll @LongRange(min = 10_000, max = 10_000_000) long premium) {
        CalcTestHarness harness = new CalcTestHarness();
        InMemoryDeferralScheduleStore schedules = new InMemoryDeferralScheduleStore();
        harness.addStep(new StepConfig(new DeferralSplitStep(),
                EffectivePeriod.from(LocalDate.of(2027, 1, 1)), 55));
        harness.addListener(new DeferralSchedulePoster(schedules));

        List<CommCalcRecord> records = harness.calculator().process(EventFixtures.newContract(
                new PolicyNo("POL-PROP"), LocalDate.of(2027, 3, 1), Money.won(premium), Map.of()));

        CommCalcRecord fy = records.stream()
                .filter(r -> r.recipientType() == RecipientType.AGENT
                        && r.commType().equals(CommTypeCode.FY_COMM))
                .findFirst().orElseThrow();

        // 분할 전 총액 = base × 7.0 × 0.9 (표준 픽스처)
        Money total = Money.won(premium)
                .multiply(ga.comm.domain.money.Rate.of("7.0"), ga.comm.domain.money.RoundingPolicy.KRW_FLOOR)
                .multiply(ga.comm.domain.money.Rate.of("0.9"), ga.comm.domain.money.RoundingPolicy.KRW_FLOOR);

        Money scheduled = schedules.all().stream()
                .map(ScheduleEntry::amount)
                .reduce(Money.ZERO, Money::plus);

        assertThat(fy.calcAmount().plus(scheduled)).isEqualTo(total);
        assertThat(schedules.all()).allSatisfy(e -> assertThat(e.amount().isPositive()).isTrue());
    }
}
