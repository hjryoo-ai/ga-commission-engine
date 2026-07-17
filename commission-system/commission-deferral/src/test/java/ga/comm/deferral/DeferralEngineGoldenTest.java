package ga.comm.deferral;

import ga.comm.calc.StepConfig;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.deferral.fixture.InMemoryDeferralScheduleStore;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.EventType;
import ga.comm.domain.type.RecipientType;
import ga.comm.rule.model.EffectivePeriod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7 골든 케이스 (설계서 §10): 분급 분할·도래·조건·소멸.
 * 완료 기준 — 4년 커브를 7년 커브로 "데이터만" 바꿔 동작 (픽스처: 2027 체결=4년, 2029 체결=7년).
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

    private CommCalcRecord agentFyRecord(List<CommCalcRecord> records) {
        return records.stream()
                .filter(r -> r.recipientType() == RecipientType.AGENT
                        && r.commType().equals(CommTypeCode.FY_COMM))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("경계: 2026-12-31 체결은 분급 미적용 — 전액 즉시 지급")
    void 경계_2026_체결은_분급_없음() {
        List<CommCalcRecord> records = harness.calculator().process(EventFixtures.newContract(
                new PolicyNo("POL-2026-DEC"), LocalDate.of(2026, 12, 31), Money.won(300_000), Map.of()));

        assertThat(agentFyRecord(records).calcAmount()).isEqualTo(Money.won(1_890_000));
        assertThat(schedules.all()).isEmpty();
    }

    @Test
    @DisplayName("골든: 2027 체결 4년 커브 — 즉시 40% + 12/24/36개월 각 20%")
    void 분급_4년_커브_분할() {
        PolicyNo policy = new PolicyNo("POL-2027-0001");
        List<CommCalcRecord> records = harness.calculator().process(EventFixtures.newContract(
                policy, LocalDate.of(2027, 6, 1), Money.won(300_000), Map.of()));

        // 즉시 지급분 = 1,890,000 × 40% = 756,000
        assertThat(agentFyRecord(records).calcAmount()).isEqualTo(Money.won(756_000));

        List<ScheduleEntry> created = schedules.all();
        assertThat(created).hasSize(3);
        assertThat(created).extracting(e -> e.dueYm().value())
                .containsExactlyInAnyOrder("202806", "202906", "203006");
        assertThat(created).allSatisfy(e -> {
            assertThat(e.amount()).isEqualTo(Money.won(378_000));
            assertThat(e.status()).isEqualTo(ScheduleStatus.SCHEDULED);
            assertThat(e.payCondition()).isEqualTo(ScheduleEntry.CONDITION_POLICY_INFORCE);
        });

        // 불변식: 스케줄 합 = 이연 원금 (1,890,000 − 756,000)
        Money scheduled = created.stream().map(ScheduleEntry::amount).reduce(Money.ZERO, Money::plus);
        assertThat(scheduled).isEqualTo(Money.won(1_134_000));
    }

    @Test
    @DisplayName("반올림: 나누어 떨어지지 않는 금액은 마지막 포인트가 잔여를 흡수한다")
    void 반올림_잔여_흡수() {
        PolicyNo policy = new PolicyNo("POL-2027-0002");
        // 월납 333,333 → FY 2,099,997 → 즉시 839,998 / 이연 1,259,999
        List<CommCalcRecord> records = harness.calculator().process(EventFixtures.newContract(
                policy, LocalDate.of(2027, 6, 1), Money.won(333_333), Map.of()));

        assertThat(agentFyRecord(records).calcAmount()).isEqualTo(Money.won(839_998));

        List<ScheduleEntry> created = schedules.all();
        assertThat(created).extracting(e -> e.amount().toLong())
                .containsExactly(419_999L, 419_999L, 420_001L);
        Money scheduled = created.stream().map(ScheduleEntry::amount).reduce(Money.ZERO, Money::plus);
        assertThat(scheduled).isEqualTo(Money.won(1_259_999));
    }

    @Test
    @DisplayName("도래 배치: 유지 중이면 RELEASED + DEFERRED 레코드, 재실행은 멱등")
    void 도래_배치_RELEASE() {
        분급_4년_커브_분할();

        DeferralReleaseService.ReleaseResult result = releaseService.release(CloseYm.of("202806"));

        assertThat(result.released()).hasSize(1);
        CommCalcRecord deferred = result.released().get(0);
        assertThat(deferred.commType()).isEqualTo(CommTypeCode.DEFERRED);
        assertThat(deferred.calcAmount()).isEqualTo(Money.won(378_000));
        assertThat(deferred.closeYm().value()).isEqualTo("202806");

        ScheduleEntry releasedEntry = schedules.all().stream()
                .filter(e -> e.status() == ScheduleStatus.RELEASED).findFirst().orElseThrow();
        assertThat(releasedEntry.releasedCalcId()).isEqualTo(deferred.calcId());

        // 멱등: 같은 월 재실행 → 추가 지급 없음
        assertThat(releaseService.release(CloseYm.of("202806")).released()).isEmpty();
    }

    @Test
    @DisplayName("지급 조건 미충족(실효 상태)이면 HELD로 보류된다")
    void 조건_미충족_HELD() {
        분급_4년_커브_분할();
        terminated.add(new PolicyNo("POL-2027-0001"));

        DeferralReleaseService.ReleaseResult result = releaseService.release(CloseYm.of("202806"));

        assertThat(result.released()).isEmpty();
        assertThat(result.held()).isEqualTo(1);
    }

    @Test
    @DisplayName("해약 이벤트는 잔여 SCHEDULED를 CANCELLED로 소멸시킨다 (RELEASED는 유지)")
    void 해약시_잔여_스케줄_소멸() {
        분급_4년_커브_분할();
        releaseService.release(CloseYm.of("202806"));  // 1건 RELEASED

        harness.calculator().process(EventFixtures.terminal(EventType.CANCEL,
                new PolicyNo("POL-2027-0001"), LocalDate.of(2027, 6, 1),
                LocalDate.of(2028, 7, 15), 13));

        assertThat(schedules.all()).extracting(ScheduleEntry::status)
                .containsExactlyInAnyOrder(ScheduleStatus.RELEASED,
                        ScheduleStatus.CANCELLED, ScheduleStatus.CANCELLED);
    }

    @Test
    @DisplayName("완료 기준: 2029 체결은 7년 커브(데이터)로 — 코드 수정 없이 즉시 30% + 7회 분급")
    void 분급_7년_커브_데이터_교체() {
        PolicyNo policy = new PolicyNo("POL-2029-0001");
        List<CommCalcRecord> records = harness.calculator().process(EventFixtures.newContract(
                policy, LocalDate.of(2029, 2, 1), Money.won(300_000), Map.of()));

        // 즉시 지급분 = 1,890,000 × 30% = 567,000
        assertThat(agentFyRecord(records).calcAmount()).isEqualTo(Money.won(567_000));

        List<ScheduleEntry> created = schedules.all();
        assertThat(created).hasSize(7);
        assertThat(created).extracting(e -> e.dueYm().value())
                .containsExactlyInAnyOrder("203002", "203102", "203202", "203302",
                        "203402", "203502", "203602");
        Money scheduled = created.stream().map(ScheduleEntry::amount).reduce(Money.ZERO, Money::plus);
        assertThat(scheduled).isEqualTo(Money.won(1_323_000));
    }
}
