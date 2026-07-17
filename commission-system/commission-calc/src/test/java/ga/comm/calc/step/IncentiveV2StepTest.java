package ga.comm.calc.step;

import ga.comm.calc.CalcContext;
import ga.comm.calc.CalculationStep;
import ga.comm.calc.StepConfig;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.rule.AmbiguousRuleException;
import ga.comm.rule.admin.IncentiveApprovalService;
import ga.comm.rule.fixture.InMemoryIncentiveStore;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.rule.incentive.IncentiveConditionEvaluator;
import ga.comm.rule.incentive.InvalidConditionException;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.IncentiveRule;
import ga.comm.rule.model.PayoutKind;
import ga.comm.rule.model.RateStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시책 계산 V2 (설계서 §3.6, Phase 13) — 조건식 등록만으로 신규 시책이 반영되고(재배포 없음),
 * 사용된 시책 버전이 rule_versions에 박제되며, 평가 오류는 계산 거부로 이어진다.
 */
class IncentiveV2StepTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate EVENT_DATE = LocalDate.of(2026, 8, 1);

    private CalcTestHarness harness;
    private InMemoryIncentiveStore incentives;
    private IncentiveApprovalService approval;

    @BeforeEach
    void setUp() {
        harness = new CalcTestHarness();
        incentives = new InMemoryIncentiveStore();
        approval = new IncentiveApprovalService(incentives);
        // V2 활성 전환 = Step 설정 (V1은 존치하되 incentive_amount 속성이 없어 휴면)
        harness.addStep(new StepConfig(
                new IncentiveV2Step(incentives, new IncentiveConditionEvaluator()),
                EffectivePeriod.from(FROM), 30));
    }

    private long approveFixed(String cd, String condition, long amount) {
        long id = approval.registerDraftFixed(cd, RuleFixtures.INSURER, RuleFixtures.PRODUCT, null,
                condition, Money.won(amount), EffectivePeriod.from(FROM)).incentiveId();
        return approval.approve(id, "팀장").incentiveId();
    }

    private List<CommCalcRecord> processNew(Map<String, String> attributes) {
        return harness.calculator().process(EventFixtures.newContract(
                new PolicyNo("POL-INC"), EVENT_DATE, Money.won(300_000), attributes));
    }

    private static Money incentiveAmount(List<CommCalcRecord> records) {
        return records.stream().filter(r -> r.commType().equals(CommTypeCode.INCENTIVE))
                .map(CommCalcRecord::calcAmount).reduce(Money.ZERO, Money::plus);
    }

    @Test
    void 조건식_등록만으로_신규_시책이_반영된다() {
        approveFixed("Q3-PUSH", "premium >= 300000 and contractCount >= 5", 500_000);

        List<CommCalcRecord> records = processNew(Map.of("perf.contractCount", "5"));

        CommCalcRecord line = records.stream()
                .filter(r -> r.commType().equals(CommTypeCode.INCENTIVE)).findFirst().orElseThrow();
        assertThat(line.calcAmount()).isEqualTo(Money.won(500_000));
        // 사용된 시책 버전이 rule_versions에 박제된다 (§6.5 replay)
        assertThat(line.ruleVersions()).contains("INCENTIVE_MST").contains("Q3-PUSH")
                .contains("v=1");
    }

    @Test
    void 조건_미충족이면_시책_라인이_없다() {
        approveFixed("Q3-PUSH", "premium >= 300000 and contractCount >= 5", 500_000);

        // contractCount 3 < 5 → 미해당 (침묵 스킵이 아니라 조건이 false)
        List<CommCalcRecord> records = processNew(Map.of("perf.contractCount", "3"));
        assertThat(incentiveAmount(records)).isEqualTo(Money.ZERO);
    }

    @Test
    void PREMIUM_RATE_산식은_보험료에_요율을_적용한다() {
        long id = approval.registerDraftPremiumRate("RATE-PUSH", RuleFixtures.INSURER,
                RuleFixtures.PRODUCT, null, "premium >= 0", ga.comm.domain.money.Rate.of("0.1"),
                EffectivePeriod.from(FROM)).incentiveId();
        approval.approve(id, "팀장");

        // 300,000 × 0.1 = 30,000
        assertThat(incentiveAmount(processNew(Map.of()))).isEqualTo(Money.won(30_000));
    }

    @Test
    void IncentiveV2는_LimitGate_앞_순서에서_라인을_만든다() {
        approveFixed("Q3-PUSH", "premium >= 300000", 500_000);
        AtomicBoolean sawIncentiveLine = new AtomicBoolean(false);
        // 스파이 Step(order 50) — 실행 시점에 시책 라인이 이미 존재하면 V2(30)가 앞서 돈 것
        CalculationStep spyAt50 = new CalculationStep() {
            @Override
            public String stepId() {
                return "SPY_50";
            }

            @Override
            public boolean supports(CalcContext ctx) {
                return true;
            }

            @Override
            public void apply(CalcContext ctx) {
                if (ctx.lines().stream().anyMatch(l -> l.commType().equals(CommTypeCode.INCENTIVE))) {
                    sawIncentiveLine.set(true);
                }
            }
        };
        harness.addStep(new StepConfig(spyAt50, EffectivePeriod.from(FROM), 50));

        processNew(Map.of());
        assertThat(sawIncentiveLine).isTrue();
    }

    @Test
    void 같은_입력은_결정적으로_같은_시책_금액을_낸다() {
        approveFixed("Q3-PUSH", "premium >= 300000 and contractCount >= 5", 500_000);

        Money first = incentiveAmount(processNew(Map.of("perf.contractCount", "7")));
        // 새 이벤트(다른 정책)로 재계산해도 동일 입력 → 동일 금액
        Money second = harness.calculator().process(EventFixtures.newContract(
                        new PolicyNo("POL-INC-2"), EVENT_DATE, Money.won(300_000),
                        Map.of("perf.contractCount", "7"))).stream()
                .filter(r -> r.commType().equals(CommTypeCode.INCENTIVE))
                .map(CommCalcRecord::calcAmount).reduce(Money.ZERO, Money::plus);
        assertThat(first).isEqualTo(Money.won(500_000)).isEqualTo(second);
    }

    @Test
    void 같은_코드에_겹치는_ACTIVE_시책이_둘이면_계산을_거부한다() {
        // 개시일이 다른 겹침 경합(ux_incentive_active가 못 막는 종류 — 동시 승인 등)을 직접 삽입(승인 우회).
        // 룰 해석이 "하나여야 하는 게 둘"이면 아무거나 고르거나 둘 다 편입(이중 지급)하지 않고 시끄럽게 실패한다.
        incentives.insert(new IncentiveRule(incentives.nextIncentiveId(), "DUP",
                RuleFixtures.INSURER, RuleFixtures.PRODUCT, null, "premium >= 0",
                PayoutKind.FIXED, Money.won(500_000), null,
                EffectivePeriod.from(LocalDate.of(2026, 1, 1)), 1L, RateStatus.ACTIVE));
        incentives.insert(new IncentiveRule(incentives.nextIncentiveId(), "DUP",
                RuleFixtures.INSURER, RuleFixtures.PRODUCT, null, "premium >= 0",
                PayoutKind.FIXED, Money.won(700_000), null,
                EffectivePeriod.from(LocalDate.of(2026, 6, 1)), 2L, RateStatus.ACTIVE));

        assertThatThrownBy(() -> processNew(Map.of()))
                .isInstanceOf(AmbiguousRuleException.class)
                .hasMessageContaining("DUP");
    }

    @Test
    void 평가_오류는_계산을_거부한다_침묵_스킵_금지() {
        // 승인 검증을 우회해 직접 삽입된 위험 조건식(ACTIVE)도 계산 시점 샌드박스가 거부한다
        incentives.insert(new IncentiveRule(incentives.nextIncentiveId(), "BYPASS",
                RuleFixtures.INSURER, RuleFixtures.PRODUCT, null,
                "T(java.lang.Runtime).getRuntime()", PayoutKind.FIXED, Money.won(1), null,
                EffectivePeriod.from(FROM), 1L, RateStatus.ACTIVE));

        assertThatThrownBy(() -> processNew(Map.of()))
                .isInstanceOf(InvalidConditionException.class);
    }
}
