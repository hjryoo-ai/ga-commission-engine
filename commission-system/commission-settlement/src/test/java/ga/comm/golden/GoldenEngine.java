package ga.comm.golden;

import ga.comm.calc.CalcResultListener;
import ga.comm.calc.CalculationPipeline;
import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.StepConfig;
import ga.comm.calc.fixture.InMemoryPolicyEventStore;
import ga.comm.calc.recipient.AffiliationBasis;
import ga.comm.calc.recipient.RecipientResolver;
import ga.comm.calc.revision.RevisionService;
import ga.comm.calc.step.BaseCommissionStep;
import ga.comm.calc.step.IncentiveStep;
import ga.comm.calc.step.OverrideCommissionStep;
import ga.comm.calc.step.PayoutRateStep;
import ga.comm.calc.store.CloseYmResolver;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.fixture.InMemoryCommCalcStore;
import ga.comm.clawback.ClawbackPostProcessor;
import ga.comm.clawback.ClawbackStep;
import ga.comm.clawback.ReviveStep;
import ga.comm.deferral.DeferralCancelListener;
import ga.comm.deferral.DeferralReleaseService;
import ga.comm.deferral.DeferralSchedulePoster;
import ga.comm.deferral.DeferralSplitStep;
import ga.comm.deferral.ScheduleEntry;
import ga.comm.deferral.fixture.InMemoryDeferralScheduleStore;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.Direction;
import ga.comm.domain.type.EventType;
import ga.comm.limit.LimitGateStep;
import ga.comm.limit.LimitLedger;
import ga.comm.limit.LimitLedgerPoster;
import ga.comm.limit.LimitLedgerService;
import ga.comm.limit.LimitReversalHook;
import ga.comm.limit.PremiumRepriceStep;
import ga.comm.limit.fixture.InMemoryLimitLedgerStore;
import ga.comm.rule.admin.RateApprovalService;
import ga.comm.rule.model.ChannelType;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.settlement.SettleCloseStore;
import ga.comm.settlement.fixture.InMemorySettleCloseStore;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 골든 케이스 실행기 (Phase 14). 케이스의 시드·피처로 <b>실제 파이프라인·서비스</b>를 조립하고
 * 타임라인을 순서대로 실행한다 — Step을 우회하지 않는다(설계서 §8.1 완료 기준).
 *
 * <p>피처 Step은 항상 활성으로 배선하고, 경계·적용 여부는 전적으로 시드 룰의 {@code apply_from}이
 * 구동한다(룰=데이터 원칙). 예: 2026-06-30 체결은 한도룰(2026-07-01 시행)이 계약일에 없어 원장이
 * 생기지 않는다 — Step 활성일이 아니라 룰 기준일이 경계를 만든다.
 */
final class GoldenEngine {

    private final GoldenCase gc;
    private final InMemoryCommCalcStore calcStore = new InMemoryCommCalcStore();
    private final InMemoryPolicyEventStore eventStore = new InMemoryPolicyEventStore();
    private final InMemoryLimitLedgerStore ledgers = new InMemoryLimitLedgerStore();
    private final InMemoryDeferralScheduleStore schedules = new InMemoryDeferralScheduleStore();
    private final InMemorySettleCloseStore closeStore = new InMemorySettleCloseStore();

    private CommissionCalculator calculator;
    private DeferralReleaseService releaseService;
    private final Map<String, List<CommCalcRecord>> recordsByRef = new LinkedHashMap<>();
    private final Map<String, PolicyEvent> eventsByRef = new LinkedHashMap<>();

    GoldenEngine(GoldenCase gc) {
        this.gc = gc;
    }

    /** 타임라인을 실행한다. 표현식/파싱 오류는 그대로 던져 케이스 실패로 표면화한다(침묵 스킵 금지). */
    void run() {
        calculator = buildCalculator();
        for (GoldenCase.Step step : gc.timeline) {
            switch (step.type()) {
                case EVENT -> runEvent(step);
                case APPROVE_RATE -> runApproveRate(step);
                case REBOOK -> runRebook(step);
                case RELEASE -> runRelease(step);
                case DEDUCT_CLAWBACK -> runDeductClawback(step);
            }
        }
    }

    private CommissionCalculator buildCalculator() {
        boolean limit = gc.features.contains("LIMIT") || gc.features.contains("CLAWBACK");
        boolean clawback = gc.features.contains("CLAWBACK");
        boolean deferral = gc.features.contains("DEFERRAL");
        LocalDate always = LocalDate.of(2000, 1, 1);

        List<StepConfig> steps = new ArrayList<>(List.of(
                new StepConfig(new BaseCommissionStep(), EffectivePeriod.from(always), 10),
                new StepConfig(new PayoutRateStep(), EffectivePeriod.from(always), 20),
                new StepConfig(new IncentiveStep(), EffectivePeriod.from(always), 30),
                new StepConfig(new OverrideCommissionStep(), EffectivePeriod.from(always), 40)));
        List<CalcResultListener> listeners = new ArrayList<>();

        if (clawback) {
            steps.add(new StepConfig(new ClawbackStep(calcStore), EffectivePeriod.from(always), 45));
            steps.add(new StepConfig(new ReviveStep(calcStore, ReviveStep.RevivePolicy.REPAY),
                    EffectivePeriod.from(always), 46));
        }
        if (limit) {
            steps.add(new StepConfig(new LimitGateStep(ledgers), EffectivePeriod.from(always), 50));
            steps.add(new StepConfig(new PremiumRepriceStep(ledgers), EffectivePeriod.from(always), 60));
            listeners.add(new LimitLedgerPoster(ledgers));
        }
        if (clawback) {
            listeners.add(new ClawbackPostProcessor(new LimitLedgerService(ledgers)));
        }
        if (deferral) {
            steps.add(new StepConfig(new DeferralSplitStep(), EffectivePeriod.from(always), 55));
            listeners.add(new DeferralSchedulePoster(schedules));
            releaseService = new DeferralReleaseService(schedules, calcStore, (policyNo, asOf) -> true);
            listeners.add(new DeferralCancelListener(releaseService));
        }

        return new CommissionCalculator(gc.rules,
                new RecipientResolver(gc.directory, AffiliationBasis.EVENT_DATE),
                new CalculationPipeline(steps), eventStore, calcStore,
                CloseYmResolver.byEventDate(), listeners);
    }

    // ---- 타임라인 단계 실행 ----

    private void runEvent(GoldenCase.Step s) {
        PolicyEvent event = buildEvent(s.cells());
        List<CommCalcRecord> produced = calculator.process(event);
        if (!s.ref().isEmpty()) {
            recordsByRef.put(s.ref(), produced);
            if (!produced.isEmpty()) {
                eventsByRef.put(s.ref(), event.withEventId(produced.get(0).eventId()));
            }
        }
    }

    private void runApproveRate(GoldenCase.Step s) {
        GoldenCsv.Row r = s.cells();
        RateApprovalService approval = new RateApprovalService(gc.rules);
        Integer installment = r.has("installment") ? Integer.parseInt(r.get("installment")) : null;
        long rateId = approval.registerDraft(Direction.OUTBOUND, gc.insurer, gc.product,
                new CommTypeCode(r.get("commType")), installment, Rate.of(r.get("rate")),
                EffectivePeriod.from(GoldenCase.date(r.get("applyFrom")))).rateId();
        approval.approve(rateId);
    }

    private void runRebook(GoldenCase.Step s) {
        GoldenCsv.Row r = s.cells();
        PolicyEvent stored = eventsByRef.get(r.get("targetRef"));
        if (stored == null) {
            throw new IllegalArgumentException(r.where()
                    + ": REBOOK targetRef가 산출 레코드를 가진 EVENT를 가리켜야 합니다: " + r.get("targetRef"));
        }
        RevisionService revision = new RevisionService(calcStore,
                SettleCloseStore.asProvider(closeStore),
                List.of(new LimitReversalHook(ledgers)));
        RevisionService.RebookResult result =
                revision.rebookEvent(stored, calculator, r.getOr("note", "골든 정정"));
        if (!s.ref().isEmpty()) {
            recordsByRef.put(s.ref(), result.rebooked());
        }
    }

    private void runRelease(GoldenCase.Step s) {
        GoldenCsv.Row r = s.cells();
        DeferralReleaseService.ReleaseResult result = releaseService.release(CloseYm.of(r.get("dueYm")));
        if (!s.ref().isEmpty()) {
            recordsByRef.put(s.ref(), result.released());
        }
    }

    private void runDeductClawback(GoldenCase.Step s) {
        GoldenCsv.Row r = s.cells();
        LocalDate asOf = GoldenCase.date(r.get("eventDate"));
        var limitRule = gc.rules.findLimitRule(ChannelType.GA_TO_AGENT, asOf)
                .orElseThrow(() -> new IllegalStateException(r.where() + ": 한도룰이 없습니다"));
        long calcId = Long.parseLong(r.getOr("calcId", "99001"));
        new LimitLedgerService(ledgers).deductForClawback(new PolicyNo(r.get("policyNo")),
                new AgentId(r.getOr("agentId", gc.agent.value())), asOf, limitRule, calcId,
                Money.won(Long.parseLong(r.get("amount"))));
    }

    private PolicyEvent buildEvent(GoldenCsv.Row r) {
        EventType type = EventType.valueOf(r.get("type"));
        PolicyNo policyNo = new PolicyNo(r.get("policyNo"));
        LocalDate eventDate = GoldenCase.date(r.get("eventDate"));
        LocalDate contractDate = r.has("contractDate") ? GoldenCase.date(r.get("contractDate")) : eventDate;
        Integer installment = r.has("installment") ? Integer.parseInt(r.get("installment")) : null;
        long premium = r.has("premium") ? Long.parseLong(r.get("premium")) : gc.defaultPremium;
        Money payment = r.has("payment") ? Money.won(Long.parseLong(r.get("payment"))) : null;
        AgentId agentId = new AgentId(r.getOr("agentId", gc.agent.value()));

        String key = gc.insurer.value() + ":" + policyNo.value() + ":" + type
                + (type == EventType.PAYMENT && installment != null ? ":" + installment : "");
        return new PolicyEvent(null, key, policyNo, gc.insurer, gc.product, type,
                eventDate, contractDate, agentId, Money.won(premium), payment, installment,
                parseAttrs(r.get("attrs")));
    }

    private static Map<String, String> parseAttrs(String raw) {
        Map<String, String> attrs = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return attrs;
        }
        for (String pair : raw.split(";")) {
            String p = pair.trim();
            if (p.isEmpty()) {
                continue;
            }
            int eq = p.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("attrs 항목은 key=value 형식이어야 합니다: " + p);
            }
            attrs.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
        }
        return attrs;
    }

    // ---- 실측 조회 (comparator 용) ----

    List<CommCalcRecord> recordsFor(String ref) {
        return recordsByRef.getOrDefault(ref, List.of());
    }

    List<CommCalcRecord> allRecords() {
        return calcStore.all();
    }

    boolean hasRef(String ref) {
        return recordsByRef.containsKey(ref);
    }

    LimitLedger ledgerFor(PolicyNo policyNo, AgentId agentId) {
        return ledgers.find(policyNo, agentId).orElse(null);
    }

    List<ScheduleEntry> schedule() {
        return schedules.all();
    }
}
