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
import ga.comm.rule.RuleRepository;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.settlement.AdjustmentService;
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

    public OutboxEngine(RuleRepository rules, AgentDirectory directory, Raw raw, Clock clock, Supplier<UUID> ids) {
        this.raw = raw;
        publisher = new OutboxPublisher(new ValidatingOutboxStore(raw.outbox()), clock, ids);
        stores = PublishingStores.wrap(publisher, raw.events(), raw.calcs(), raw.ledgers(), raw.schedules(), raw.receivables(),
                raw.adjustments());

        // 운영 조립과 같은 순서: 마감월 가드(바깥) → 발행 데코레이터(E2 ④)
        calcs = new ClosedMonthGuardedCalcStore(stores.calcs(), raw.close());
        CloseStatusProvider closeStatus = SettleCloseStore.asProvider(raw.close());
        CloseYmResolver resolver = event -> closeStatus.attributionFor(CloseYm.from(event.eventDate()));
        releaseService = new DeferralReleaseService(stores.schedules(), calcs, (policyNo, asOf) -> true);

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
        calculator = new CommissionCalculator(rules, new RecipientResolver(directory, AffiliationBasis.EVENT_DATE),
                new CalculationPipeline(steps), stores.events(), calcs, resolver, listeners);

        revision = new RevisionService(calcs, closeStatus,
                List.of(new LimitReversalHook(stores.ledgers()), new DeferralReversalHook(stores.schedules())));
        List<CloseHook> closeHooks = List.of(new DeferralReleaseCloseHook(releaseService));
        closeService = new MonthCloseService(calcs, stores.events(), stores.ledgers(), raw.close(), closeHooks, clock,
                List.of(stores.statementPublisher()));
        payoutService = new PayoutService(calcs, raw.settlements(), raw.close(),
                new ClawbackOffsetService(stores.receivables()), WithholdingTaxPolicy.STANDARD_3_3, raw.statements(), clock,
                List.of(stores.payoutPublisher()));
        adjustmentService = new AdjustmentService(calcs, closeStatus, stores.adjustments());
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
