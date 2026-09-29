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
import ga.comm.domain.testing.SeededCases;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 불변식 (설계서 §8.2): 임의 금액에서 분급 스케줄 합 = 이연 원금.
 * 시드 고정 생성기 — jqwik 대체(Phase E3-0). 원본 {@code tries = 100}을 경계값 + 무작위 100건으로 유지.
 */
class DeferralSplitPropertyTest {

    static Stream<Arguments> premiums() {
        return SeededCases.withEdges(0x5EED_E321L, 100,
                List.<Object[]>of(new Object[] {10_000L}, new Object[] {10_000_000L}, new Object[] {10_001L},
                        new Object[] {9_999_999L}),
                r -> new Object[] {SeededCases.longIn(r, 10_000, 10_000_000)});
    }

    @ParameterizedTest
    @MethodSource("premiums")
    void 분급_스케줄_합은_항상_이연_원금과_같다(long premium) {
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
