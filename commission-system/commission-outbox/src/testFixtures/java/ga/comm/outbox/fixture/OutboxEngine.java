package ga.comm.outbox.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import ga.comm.calc.CalcResultListener;
import ga.comm.calc.CalculationPipeline;
import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.StepConfig;
import ga.comm.calc.recipient.AffiliationBasis;
import ga.comm.calc.recipient.AgentDirectory;
import ga.comm.calc.recipient.RecipientResolver;
import ga.comm.calc.revision.RevisionService;
import ga.comm.calc.step.BaseCommissionStep;
import ga.comm.calc.step.IncentiveStep;
import ga.comm.calc.step.OverrideCommissionStep;
import ga.comm.calc.step.PayoutRateStep;
import ga.comm.calc.store.CloseStatusProvider;
import ga.comm.calc.store.CloseYmResolver;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.calc.store.PolicyEventStore;
import ga.comm.clawback.ClawbackOffsetService;
import ga.comm.clawback.ClawbackPostProcessor;
import ga.comm.clawback.ClawbackReceivableStore;
import ga.comm.clawback.ClawbackStep;
import ga.comm.clawback.ReviveStep;
import ga.comm.deferral.DeferralCancelListener;
import ga.comm.deferral.DeferralReleaseService;
import ga.comm.deferral.DeferralReversalHook;
import ga.comm.deferral.DeferralScheduleStore;
import ga.comm.deferral.DeferralSchedulePoster;
import ga.comm.deferral.DeferralSplitStep;
import ga.comm.domain.time.CloseYm;
import ga.comm.limit.LimitGateStep;
import ga.comm.limit.LimitLedgerPoster;
import ga.comm.limit.LimitLedgerService;
import ga.comm.limit.LimitLedgerStore;
import ga.comm.limit.LimitReversalHook;
import ga.comm.limit.PremiumRepriceStep;
import ga.comm.outbox.EnvelopeJson;
import ga.comm.outbox.OutboxStore;
import ga.comm.outbox.StoredEvent;
import ga.comm.outbox.publish.OutboxPublisher;
import ga.comm.outbox.publish.PublishingStores;
import ga.comm.rule.IncentiveRepository;
import ga.comm.rule.RuleRepository;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.settlement.AdjustmentService;
import ga.comm.settlement.estimate.EstimateReads;
import ga.comm.settlement.simulation.DryRunSimulator;
import ga.comm.settlement.simulation.SimulationReads;
import ga.comm.settlement.estimate.StatementEstimator;
import ga.comm.outbox.publish.EstimatePublisher;
import ga.comm.deferral.PolicyStatusProvider;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import ga.comm.settlement.ClosedMonthGuardedCalcStore;
import ga.comm.settlement.AgentSettlementStore;
import ga.comm.settlement.CloseHook;
import ga.comm.settlement.DeferralReleaseCloseHook;
import ga.comm.settlement.MonthCloseService;
import ga.comm.settlement.PayoutService;
import ga.comm.settlement.PayoutStatementStore;
import ga.comm.settlement.SettleCloseStore;
import ga.comm.settlement.WithholdingTaxPolicy;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 아웃박스 시험용 엔진 조립 — 앱 조립({@code CalcPipelineConfig}·{@code ServiceConfig}·{@code PersistenceConfig})과 같은 Step·리스너·훅·서비스를
 * 발행 데코레이터({@link PublishingStores}) 위에 세운다. 저장소는 호출자가 준다(인메모리 — 아웃박스 시험, Oracle — 인프라 IT).
 * 아웃박스는 전역 검증기({@link ValidatingOutboxStore})로 감싼다(C1).
 */
public final class OutboxEngine {

    private static final LocalDate STEPS_FROM = LocalDate.of(2026, 1, 1);

    /** 감싸기 전 저장소(엔진이 쓰는 것은 감싼 것, 시험이 읽는 것은 이것). */
    public record Raw(PolicyEventStore events, CommCalcStore calcs, LimitLedgerStore ledgers, DeferralScheduleStore schedules,
                      ClawbackReceivableStore receivables, AdjustmentService.AdjustmentStore adjustments, SettleCloseStore close,
                      AgentSettlementStore settlements, PayoutStatementStore statements, OutboxStore outbox) {
    }

    public final Raw raw;
    public final OutboxPublisher publisher;
    public final PublishingStores stores;
    /** 엔진이 쓰는 계산 저장소 — 마감월 가드가 발행 데코레이터를 감싼 것(운영과 같다). */
    public final CommCalcStore calcs;
    public final CommissionCalculator calculator;
    public final RevisionService revision;
    public final DeferralReleaseService releaseService;
    public final MonthCloseService closeService;
    public final PayoutService payoutService;
    public final AdjustmentService adjustmentService;
    /** 잠정 명세 산출기(E2 ①) — 읽기 함수 묶음만 받는다. */
    public final StatementEstimator estimator;
    public final EstimatePublisher estimatePublisher;
    private final Clock clock;
    private final RuleRepository rules;
    private final RecipientResolver recipients;
    private final CloseYmResolver resolver;
    private final PolicyStatusProvider policyStatus;
    private final WithholdingTaxPolicy taxPolicy;
    private final EstimateReads estimateReads;

    public OutboxEngine(RuleRepository rules, AgentDirectory directory, Raw raw, Clock clock, Supplier<UUID> ids) {
        this.raw = raw;
        publisher = new OutboxPublisher(new ValidatingOutboxStore(raw.outbox()), clock, ids);
        stores = PublishingStores.wrap(publisher, raw.events(), raw.calcs(), raw.ledgers(), raw.schedules(), raw.receivables(),
                raw.adjustments());

        // 운영 조립과 같은 순서: 마감월 가드(바깥) → 발행 데코레이터(E2 ④)
        calcs = new ClosedMonthGuardedCalcStore(stores.calcs(), raw.close());
        CloseStatusProvider closeStatus = SettleCloseStore.asProvider(raw.close());
        CloseYmResolver resolver = event -> closeStatus.attributionFor(CloseYm.from(event.eventDate()));
        PolicyStatusProvider policyStatus = (policyNo, asOf) -> true;
        releaseService = new DeferralReleaseService(stores.schedules(), calcs, policyStatus);
        WithholdingTaxPolicy taxPolicy = WithholdingTaxPolicy.STANDARD_3_3;   // 지급 런과 잠정 명세가 같은 인스턴스(E2 P9)

        EffectivePeriod from = EffectivePeriod.from(STEPS_FROM);
        List<StepConfig> steps = List.of(
                new StepConfig(new BaseCommissionStep(), from, 10),
                new StepConfig(new PayoutRateStep(), from, 20),
                new StepConfig(new IncentiveStep(), from, 30),
                new StepConfig(new OverrideCommissionStep(), from, 40),
                new StepConfig(new ClawbackStep(calcs), from, 45),
                new StepConfig(new ReviveStep(calcs, ReviveStep.RevivePolicy.REPAY), from, 46),
                new StepConfig(new LimitGateStep(stores.ledgers()), from, 50),
                new StepConfig(new DeferralSplitStep(), from, 55),
                new StepConfig(new PremiumRepriceStep(stores.ledgers()), from, 60));
        List<CalcResultListener> listeners = List.of(
                new LimitLedgerPoster(stores.ledgers()),
                new ClawbackPostProcessor(new LimitLedgerService(stores.ledgers())),
                new DeferralSchedulePoster(stores.schedules()),
                new DeferralCancelListener(releaseService));
        RecipientResolver recipients = new RecipientResolver(directory, AffiliationBasis.EVENT_DATE);
        calculator = new CommissionCalculator(rules, recipients,
                new CalculationPipeline(steps), stores.events(), calcs, resolver, listeners);

        revision = new RevisionService(calcs, closeStatus,
                List.of(new LimitReversalHook(stores.ledgers()), new DeferralReversalHook(stores.schedules())));
        List<CloseHook> closeHooks = List.of(new DeferralReleaseCloseHook(releaseService));
        closeService = new MonthCloseService(calcs, stores.events(), stores.ledgers(), raw.close(), closeHooks, clock,
                List.of(stores.statementPublisher()));
        payoutService = new PayoutService(calcs, raw.settlements(), raw.close(),
                new ClawbackOffsetService(stores.receivables()), taxPolicy, raw.statements(), clock,
                List.of(stores.payoutPublisher()));
        adjustmentService = new AdjustmentService(calcs, closeStatus, stores.adjustments());
        DeferralScheduleStore schedules = stores.schedules();
        ClawbackReceivableStore receivables = stores.receivables();
        AdjustmentService.AdjustmentStore adjustments = stores.adjustments();
        estimateReads = new EstimateReads(calcs::findByCloseYm, calcs::findById, schedules::findDue, policyStatus,
                receivables::findOffsettable, adjustments::findByCloseYm, raw.close()::stateOf);
        estimator = new StatementEstimator(estimateReads, taxPolicy);
        estimatePublisher = new EstimatePublisher(publisher);
        this.clock = clock;
        this.rules = rules;
        this.recipients = recipients;
        this.resolver = resolver;
        this.policyStatus = policyStatus;
        this.taxPolicy = taxPolicy;
    }

    /**
     * 시뮬레이터(E2 ②) — 앱 조립({@code SimulationConfig})과 같다: 엔진이 쓰는 저장소(가드·발행 데코레이터)의 <b>읽기 메서드 참조</b>만,
     * 원장은 {@code peek}. 산출기의 읽기 묶음도 같은 것.
     */
    public DryRunSimulator simulator(IncentiveRepository incentives, Predicate<String> agentExists) {
        CommCalcStore c = calcs;
        LimitLedgerStore l = stores.ledgers();
        DeferralScheduleStore s = stores.schedules();
        PolicyEventStore e = stores.events();
        SimulationReads reads = new SimulationReads(c::findById, c::findByEventId, c::findByPolicyAndRecipient, c::findByCloseYm,
                l::peek, s::findDue, s::findByPolicyAndAgent, s::findBySourceCalcId, e::findById, agentExists);
        return new DryRunSimulator(rules, incentives, recipients, resolver, policyStatus, reads, estimateReads, taxPolicy, clock,
                DryRunSimulator.ReadTx.DIRECT);
    }

    /**
     * 잠정 명세 잡과 같은 순서(E2 ① — {@code statementEstimateJob}): ⓐ 미부여 행 순번 부여 ⓑ {@code basisSeq} = 그 뒤 최대 부여 순번(하한, Q3)
     * ⓒ 산출 ⓓ 설계사마다 적재. {@code asOf} = 시계(초 단위).
     */
    public List<StatementEstimator.Estimate> estimateAndPublish(CloseYm ym) {
        raw.outbox().assignPending(10_000);
        long basisSeq = raw.outbox().headSeq();
        OffsetDateTime asOf = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        List<StatementEstimator.Estimate> estimates = estimator.estimate(ym);
        for (StatementEstimator.Estimate e : estimates) {
            estimatePublisher.publish(e, basisSeq, asOf);
        }
        return estimates;
    }

    /** 결정론 eventId(골든 스냅샷) — 00000000-0000-0000-0000-{n}. */
    public static Supplier<UUID> sequentialIds() {
        long[] n = {0};
        return () -> new UUID(0L, ++n[0]);
    }

    /** 순번 {@code afterSeq} 뒤에 붙은 이벤트(Envelope) — 미부여 행에 먼저 순번을 붙인다(인메모리·단독 호출 전용). */
    public List<JsonNode> eventsAfter(long afterSeq) {
        raw.outbox().assignPending(10_000);
        List<JsonNode> out = new ArrayList<>();
        for (StoredEvent e : raw.outbox().readAssignedAfter(afterSeq, 10_000)) {
            out.add(EnvelopeJson.render(e));
        }
        return out;
    }
}
