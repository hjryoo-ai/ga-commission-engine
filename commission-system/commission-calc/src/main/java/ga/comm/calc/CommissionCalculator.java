package ga.comm.calc;

import ga.comm.calc.recipient.CalcTarget;
import ga.comm.calc.recipient.RecipientResolver;
import ga.comm.calc.store.CloseYmResolver;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.calc.store.PolicyEventStore;
import ga.comm.calc.util.JsonLite;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.ProcessStatus;
import ga.comm.rule.RuleRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 계산 오케스트레이터 (설계서 §4.2):
 * 이벤트 멱등 수신 → 수급자 결정 → 룰 스냅샷 → Step 체인 → COMM_CALC 저장(CALCULATED).
 *
 * <p>멱등성: 같은 event_key의 재수신은 기존 계산 결과를 반환하고 재계산하지 않는다.
 * FAILED 이벤트의 재수신만 재시도로 취급한다. (DB 구현은 이 메서드 전체를 단일 트랜잭션으로 감싼다.)
 */
public class CommissionCalculator {

    private final RuleRepository ruleRepository;
    private final RecipientResolver recipientResolver;
    private final CalculationPipeline pipeline;
    private final PolicyEventStore eventStore;
    private final CommCalcStore calcStore;
    private final CloseYmResolver closeYmResolver;
    private final List<CalcResultListener> listeners;

    public CommissionCalculator(RuleRepository ruleRepository, RecipientResolver recipientResolver,
                                CalculationPipeline pipeline, PolicyEventStore eventStore,
                                CommCalcStore calcStore, CloseYmResolver closeYmResolver) {
        this(ruleRepository, recipientResolver, pipeline, eventStore, calcStore, closeYmResolver, List.of());
    }

    public CommissionCalculator(RuleRepository ruleRepository, RecipientResolver recipientResolver,
                                CalculationPipeline pipeline, PolicyEventStore eventStore,
                                CommCalcStore calcStore, CloseYmResolver closeYmResolver,
                                List<CalcResultListener> listeners) {
        this.ruleRepository = Objects.requireNonNull(ruleRepository);
        this.recipientResolver = Objects.requireNonNull(recipientResolver);
        this.pipeline = Objects.requireNonNull(pipeline);
        this.eventStore = Objects.requireNonNull(eventStore);
        this.calcStore = Objects.requireNonNull(calcStore);
        this.closeYmResolver = Objects.requireNonNull(closeYmResolver);
        this.listeners = List.copyOf(listeners);
    }

    public List<CommCalcRecord> process(PolicyEvent rawEvent) {
        PolicyEventStore.UpsertResult stored = eventStore.upsertByKey(rawEvent);
        PolicyEvent event = stored.event();

        if (stored.duplicate() && stored.status() != ProcessStatus.FAILED) {
            return calcStore.findByEventId(event.eventId());
        }

        try {
            List<CommCalcRecord> saved = calculateAndPersist(event);
            eventStore.markProcessed(event.eventId());
            return saved;
        } catch (RuntimeException e) {
            eventStore.markFailed(event.eventId(), e.getMessage());
            throw e;
        }
    }

    /**
     * 재계산(Rebook, §6.4) — 멱등키 검사 없이 이미 저장된 이벤트를 다시 계산·저장한다.
     * 반드시 기존 활성 레코드의 reversal 이후에 호출되어야 한다 ({@code RevisionService}가 보장).
     */
    public List<CommCalcRecord> rebook(PolicyEvent event) {
        if (event.eventId() == null) {
            throw new IllegalArgumentException("rebook은 저장된 이벤트(eventId 보유)만 가능합니다");
        }
        return calculateAndPersist(event);
    }

    /** 저장 없이 파이프라인만 실행 — 시뮬레이션·replay 용. 리스너도 호출하지 않는다. */
    public List<CalcContext> dryRun(PolicyEvent event) {
        List<CalcContext> contexts = new ArrayList<>();
        for (CalcTarget target : recipientResolver.resolve(event)) {
            RuleSnapshot snapshot = new RuleSnapshot(ruleRepository, event);
            CalcContext ctx = new CalcContext(event, target, snapshot);
            pipeline.run(ctx);
            contexts.add(ctx);
        }
        return contexts;
    }

    private List<CommCalcRecord> calculateAndPersist(PolicyEvent event) {
        List<CommCalcRecord> saved = new ArrayList<>();
        CloseYm closeYm = closeYmResolver.resolveFor(event);

        for (CalcTarget target : recipientResolver.resolve(event)) {
            RuleSnapshot snapshot = new RuleSnapshot(ruleRepository, event);
            CalcContext ctx = new CalcContext(event, target, snapshot);
            pipeline.run(ctx);

            String ruleVersions = JsonLite.ruleVersions(snapshot.usedRuleVersions());
            String trace = JsonLite.trace(ctx.traceEntries());

            List<CalcResultListener.PersistedLine> persisted = new ArrayList<>();
            for (int i = 0; i < ctx.lines().size(); i++) {
                CalcLine line = ctx.lines().get(i);
                if (line.amount().isZero() && line.limitCutAmount().isZero()) {
                    continue;
                }
                CommCalcRecord record = calcStore.insert(
                        CommCalcRecord.calculated(event, target, line, closeYm, ruleVersions, trace));
                saved.add(record);
                persisted.add(new CalcResultListener.PersistedLine(i, line, record));
            }
            for (CalcResultListener listener : listeners) {
                listener.onPersisted(ctx, persisted);
            }
        }
        return saved;
    }
}
