package ga.comm.calc.fixture;

import ga.comm.calc.CalcResultListener;
import ga.comm.calc.CalculationPipeline;
import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.StepConfig;
import ga.comm.calc.recipient.AffiliationBasis;
import ga.comm.calc.recipient.RecipientResolver;
import ga.comm.calc.step.BaseCommissionStep;
import ga.comm.calc.step.IncentiveStep;
import ga.comm.calc.step.OverrideCommissionStep;
import ga.comm.calc.step.PayoutRateStep;
import ga.comm.calc.store.CloseYmResolver;
import ga.comm.rule.fixture.InMemoryRuleStore;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.rule.model.EffectivePeriod;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 계산 파이프라인 테스트 하네스 — 표준 룰/설계사/스토어를 조립한다.
 * 이후 Phase의 Step(LimitGate, DeferralSplit 등)은 {@link #extraSteps}로 끼워 넣는다.
 */
public class CalcTestHarness {

    public static final LocalDate STEPS_ACTIVE_FROM = LocalDate.of(2026, 1, 1);

    public final InMemoryRuleStore rules;
    public final InMemoryAgentDirectory directory;
    public final InMemoryPolicyEventStore eventStore;
    public final InMemoryCommCalcStore calcStore;
    public final List<StepConfig> extraSteps = new ArrayList<>();
    public final List<CalcResultListener> listeners = new ArrayList<>();
    public CloseYmResolver closeYmResolver = CloseYmResolver.byEventDate();

    public CalcTestHarness() {
        this(RuleFixtures.standardRules());
    }

    public CalcTestHarness(InMemoryRuleStore rules) {
        this.rules = rules;
        this.directory = standardDirectory();
        this.eventStore = new InMemoryPolicyEventStore();
        this.calcStore = new InMemoryCommCalcStore();
    }

    /** 설계사 A-1001: SR 등급, 팀 T1 → 지점 B1 → 본부 H1 소속. */
    public static InMemoryAgentDirectory standardDirectory() {
        return new InMemoryAgentDirectory()
                .withGrade(EventFixtures.AGENT_A, RuleFixtures.GRADE_SENIOR, LocalDate.of(2025, 1, 1))
                .withOrgChain(EventFixtures.AGENT_A, LocalDate.of(2025, 1, 1),
                        InMemoryAgentDirectory.team("T1"),
                        InMemoryAgentDirectory.branch("B1"),
                        InMemoryAgentDirectory.hq("H1"));
    }

    public CalcTestHarness addStep(StepConfig config) {
        extraSteps.add(config);
        return this;
    }

    public CalcTestHarness addListener(CalcResultListener listener) {
        listeners.add(listener);
        return this;
    }

    public CommissionCalculator calculator() {
        List<StepConfig> steps = new ArrayList<>(List.of(
                new StepConfig(new BaseCommissionStep(), EffectivePeriod.from(STEPS_ACTIVE_FROM), 10),
                new StepConfig(new PayoutRateStep(), EffectivePeriod.from(STEPS_ACTIVE_FROM), 20),
                new StepConfig(new IncentiveStep(), EffectivePeriod.from(STEPS_ACTIVE_FROM), 30),
                new StepConfig(new OverrideCommissionStep(), EffectivePeriod.from(STEPS_ACTIVE_FROM), 40)
        ));
        steps.addAll(extraSteps);
        return new CommissionCalculator(
                rules,
                new RecipientResolver(directory, AffiliationBasis.EVENT_DATE),
                new CalculationPipeline(steps),
                eventStore,
                calcStore,
                closeYmResolver,
                listeners);
    }
}
