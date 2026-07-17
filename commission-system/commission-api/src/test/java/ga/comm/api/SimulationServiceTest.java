package ga.comm.api;

import ga.comm.domain.money.Money;
import ga.comm.rule.fixture.RuleFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9 골든: "이 계약 체결 시 초년도 예상 수수료/한도 소진" (설계서 §10).
 *
 * <p>표준 픽스처 — 월납 300,000 SR: NEW 1,890,000 + 2~12회차 11×40,500 = 445,500
 * → FY 소계 2,335,500. 한도 3,600,000.
 */
class SimulationServiceTest {

    private final SimulationService service = new SimulationService(RuleFixtures.standardRules());

    private SimulationService.SimulationRequest request(LocalDate contractDate, Money incentive) {
        return new SimulationService.SimulationRequest(RuleFixtures.INSURER, RuleFixtures.PRODUCT,
                contractDate, Money.won(300_000), RuleFixtures.GRADE_SENIOR, incentive);
    }

    @Test
    void 한도_내_계약_시뮬레이션() {
        SimulationService.SimulationResult result =
                service.simulate(request(LocalDate.of(2026, 8, 1), Money.won(1_000_000)));

        assertThat(result.firstYearTotal()).isEqualTo(Money.won(2_335_500 + 1_000_000));
        assertThat(result.limitIncludedTotal()).isEqualTo(Money.won(3_335_500));
        assertThat(result.limitAmount()).isEqualTo(Money.won(3_600_000));
        assertThat(result.overLimitAmount()).isEqualTo(Money.ZERO);
        assertThat(result.utilizationPct()).isEqualByComparingTo("92.65");
        assertThat(result.deferralApplies()).isFalse();
    }

    @Test
    void 한도_초과_예상액을_사전에_보여준다() {
        SimulationService.SimulationResult result =
                service.simulate(request(LocalDate.of(2026, 8, 1), Money.won(2_000_000)));

        // 4,335,500 − 3,600,000 = 735,500 초과 예상
        assertThat(result.overLimitAmount()).isEqualTo(Money.won(735_500));
    }

    @Test
    void 한도룰_미적용_계약은_한도_없음으로_표시된다() {
        SimulationService.SimulationResult result =
                service.simulate(request(LocalDate.of(2026, 6, 30), Money.won(2_000_000)));

        assertThat(result.limitAmount()).isNull();
        assertThat(result.overLimitAmount()).isEqualTo(Money.ZERO);
        assertThat(result.utilizationPct()).isNull();
    }

    @Test
    void 분급_적용_계약은_초년도_즉시_수령분을_보여준다() {
        SimulationService.SimulationResult result =
                service.simulate(request(LocalDate.of(2027, 3, 1), null));

        assertThat(result.deferralApplies()).isTrue();
        // FY 라인 즉시 40%: floor(1,890,000×0.4) + 11×floor(40,500×0.4) = 756,000 + 11×16,200
        assertThat(result.immediateFirstYear()).isEqualTo(Money.won(756_000 + 178_200));
    }
}
