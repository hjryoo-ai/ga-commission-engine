package ga.comm.deferral;

import ga.comm.calc.StepConfig;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.deferral.fixture.InMemoryDeferralScheduleStore;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.rule.model.EffectivePeriod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 분급 엔진의 <b>지급 조건 거동</b> 테스트 (설계서 §10).
 *
 * <p>분급 분할·도래·반올림·해약 소멸의 <b>골든 값</b> 케이스는 CSV 골든셋으로 이관됐다(Phase 14 —
 * {@code golden/cases/16,17,18,19,23,24}). 이 파일에는 CSV로 표현하기 어려운 거동 —
 * "지급 조건 미충족(실효 상태)이면 도래분이 HELD로 보류된다" — 만 남긴다. HELD는 유지 여부 판정
 * 콜백(테스트 하네스 제어)에 의존하므로 골든 CSV 러너(유지=참 고정)로는 재현하지 않는다.
 */
class DeferralEngineGoldenTest {

    private CalcTestHarness harness;
    private InMemoryDeferralScheduleStore schedules;
    private DeferralReleaseService releaseService;
    private final Set<PolicyNo> terminated = new HashSet<>();

    @BeforeEach
    void setUp() {
        harness = new CalcTestHarness();
        schedules = new InMemoryDeferralScheduleStore();
        harness.addStep(new StepConfig(new DeferralSplitStep(),
                EffectivePeriod.from(LocalDate.of(2027, 1, 1)), 55));
        harness.addListener(new DeferralSchedulePoster(schedules));

        releaseService = new DeferralReleaseService(schedules, harness.calcStore,
                (policyNo, asOf) -> !terminated.contains(policyNo));
        harness.addListener(new DeferralCancelListener(releaseService));
    }

    @Test
    @DisplayName("지급 조건 미충족(실효 상태)이면 도래분이 HELD로 보류된다")
    void 조건_미충족_HELD() {
        // 2027 체결 4년 커브 계약 → 202806 도래분 1건 스케줄 (골든 값 검증은 CSV 17에서)
        harness.calculator().process(EventFixtures.newContract(
                new PolicyNo("POL-2027-0001"), LocalDate.of(2027, 6, 1), Money.won(300_000), Map.of()));
        terminated.add(new PolicyNo("POL-2027-0001"));

        DeferralReleaseService.ReleaseResult result = releaseService.release(CloseYm.of("202806"));

        assertThat(result.released()).isEmpty();
        assertThat(result.held()).isEqualTo(1);
    }
}
