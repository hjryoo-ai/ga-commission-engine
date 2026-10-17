package ga.comm.settlement.simulation;

import ga.comm.calc.CalcContext;
import ga.comm.calc.CalcResultListener;
import ga.comm.calc.CalculationPipeline;
import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.RuleVersionRef;
import ga.comm.calc.recipient.RecipientResolver;
import ga.comm.calc.store.CloseYmResolver;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.domain.type.RecipientType;
import ga.comm.deferral.DeferralReleaseService;
import ga.comm.deferral.PolicyStatusProvider;
import ga.comm.deferral.ScheduleEntry;
import ga.comm.deferral.ScheduleStatus;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.Direction;
import ga.comm.domain.type.EventType;
import ga.comm.limit.LimitLedger;
import ga.comm.limit.PremiumRepriceStep;
import ga.comm.rule.IncentiveRepository;
import ga.comm.rule.RuleRepository;
import ga.comm.rule.model.IncentiveRule;
import ga.comm.settlement.CalcSteps;
import ga.comm.settlement.WithholdingTaxPolicy;
import ga.comm.settlement.estimate.EstimateReads;
import ga.comm.settlement.estimate.StatementEstimator;
import ga.comm.settlement.simulation.SimulationModel.CancelledDeferral;
import ga.comm.settlement.simulation.SimulationModel.Deferral;
import ga.comm.settlement.simulation.SimulationModel.DueSlice;
import ga.comm.settlement.simulation.SimulationModel.Incentive;
import ga.comm.settlement.simulation.SimulationModel.Installment;
import ga.comm.settlement.simulation.SimulationModel.MonthEffect;
import ga.comm.settlement.simulation.SimulationModel.NewContractRequest;
import ga.comm.settlement.simulation.SimulationModel.NewContractResult;
import ga.comm.settlement.simulation.SimulationModel.PolicyEventRequest;
import ga.comm.settlement.simulation.SimulationModel.PolicyEventResult;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 시뮬레이션 2종(E2 ②, 포털 계약 {@code POST /internal/v1/simulations/new-contract}·{@code /policy-event}) — <b>운영 계산기와 같은 Step 목록·
 * 같은 저장 후 훅</b>({@link CalcSteps} — 주입 S12)을 <b>오버레이 저장소</b>로 조립한 두 번째 계산기로 가상 이벤트를 돌린다(계획 P3·Q1).
 *
 * <ul>
 *   <li>오버레이는 원 저장소의 읽기 함수({@link SimulationReads})만 받는다 — 운영 발행 데코레이터를 받을 수 없다(생성자 시그니처). 삽입·전기·
 *       전이·스케줄은 메모리에만, ID는 음수. 원장은 잠그지 않는 조회로 사본을 만든다(C3).</li>
 *   <li>업무 표·아웃박스에 아무것도 쓰지 않는다(C1 — {@code DryRunNoWriteOracleIT}).</li>
 *   <li>{@code ruleSnapshotId} = 그 시뮬레이션의 룰 버전 묶음({@link RuleSnapshotId}), {@code generatedAt} = 주입된 시계.</li>
 * </ul>
 */
public final class DryRunSimulator {

    /** 신계약 시뮬레이션의 가상 증권번호 — 원장 조회 키. 운영 증권번호와 겹치지 않는 접두사. */
    public static final String SIM_POLICY_NO = "SIM-NEW-CONTRACT";
    /** 회차 상한의 안전판(유효 FY 요율이 끝없이 이어지는 설정 오류 방지). */
    static final int MAX_INSTALLMENTS = 120;

    /** 읽기 트랜잭션 — 앱은 읽기 전용 트랜잭션 템플릿, 인메모리는 그대로 실행. */
    public interface ReadTx {
        <T> T read(Supplier<T> work);

        ReadTx DIRECT = new ReadTx() {
            @Override
            public <T> T read(Supplier<T> work) {
                return work.get();
            }
        };
    }

    private final RuleRepository rules;
    private final IncentiveRepository incentives;
    private final RecipientResolver recipients;
    private final CloseYmResolver closeYmResolver;
    private final PolicyStatusProvider policyStatus;
    private final SimulationReads reads;
    private final EstimateReads estimateReads;
    private final WithholdingTaxPolicy taxPolicy;
    private final Clock clock;
    private final ReadTx tx;

    public DryRunSimulator(RuleRepository rules, IncentiveRepository incentives, RecipientResolver recipients,
                           CloseYmResolver closeYmResolver, PolicyStatusProvider policyStatus, SimulationReads reads,
                           EstimateReads estimateReads, WithholdingTaxPolicy taxPolicy, Clock clock, ReadTx tx) {
        this.rules = Objects.requireNonNull(rules);
        this.incentives = Objects.requireNonNull(incentives);
        this.recipients = Objects.requireNonNull(recipients);
        this.closeYmResolver = Objects.requireNonNull(closeYmResolver);
        this.policyStatus = Objects.requireNonNull(policyStatus);
        this.reads = Objects.requireNonNull(reads);
        this.estimateReads = Objects.requireNonNull(estimateReads);
        this.taxPolicy = Objects.requireNonNull(taxPolicy);
        this.clock = Objects.requireNonNull(clock);
        this.tx = Objects.requireNonNull(tx);
    }

    /** 오버레이 계산기의 Step 클래스(실행 순서) — 운영 조립과의 일치 시험(C7, 주입 S12). */
    public List<Class<?>> pipelineStepClasses() {
        return new Run(incentives).calculator.pipeline().stepClasses();
    }

    /** 오버레이 계산기의 저장 후 훅 클래스(순서, 결과 수집 훅 제외) — 운영 조립과의 일치 시험(C7). */
    public List<Class<?>> listenerClasses() {
        return new Run(incentives).calculator.listeners().stream()
                .filter(l -> !(l instanceof Capture))
                .<Class<?>>map(Object::getClass).toList();
    }

    // ---- new-contract ----

    public NewContractResult simulateNewContract(NewContractRequest req) {
        if (req.monthlyPremium() < 0) {
            throw new SimulationRejected(SimulationRejected.Kind.INVALID_REQUEST, "monthlyPremium must be >= 0");
        }
        if (req.paymentTermMonths() != null && req.paymentTermMonths() < 1) {
            throw new SimulationRejected(SimulationRejected.Kind.INVALID_REQUEST, "paymentTermMonths must be >= 1");
        }
        return tx.read(() -> {
            requireAgent(req.agentId());
            ProductKey product = new ProductKey(req.productKey());
            InsurerCode insurer = req.insurerCd() != null ? new InsurerCode(req.insurerCd()) : soleInsurer(product, req);
            FilteredIncentives filtered = new FilteredIncentives(incentives, req.incentiveCodes());
            Run run = new Run(filtered);

            AgentId agent = new AgentId(req.agentId());
            PolicyNo policyNo = new PolicyNo(SIM_POLICY_NO);
            Money premium = Money.won(req.monthlyPremium());
            int cap = Math.min(req.paymentTermMonths() == null ? MAX_INSTALLMENTS : req.paymentTermMonths(), MAX_INSTALLMENTS);

            // 회차 1 = 신계약 성립(NEW), 2..N = 초년도 회차 입금(PAYMENT) — FY 요율이 있는 회차까지, 납입기간으로 상한
            List<PolicyEvent> events = new ArrayList<>();
            events.add(new PolicyEvent(null, "SIM:NEW", policyNo, insurer, product, EventType.NEW, req.contractDate(),
                    req.contractDate(), agent, premium, null, null, Map.of()));
            for (int no = 2; no <= cap; no++) {
                var date = req.contractDate().plusMonths(no - 1L);
                if (rules.findRate(Direction.OUTBOUND, insurer, product, CommTypeCode.FY_COMM, no, date).isEmpty()) {
                    break;
                }
                events.add(new PolicyEvent(null, "SIM:PAYMENT:" + no, policyNo, insurer, product, EventType.PAYMENT, date,
                        req.contractDate(), agent, premium, null, no, Map.of()));
            }

            List<Installment> fy = new ArrayList<>();
            Map<String, long[]> incentiveSums = new LinkedHashMap<>();   // code → {amount, cut}
            Map<String, Boolean> incentiveIncluded = new HashMap<>();
            for (int i = 0; i < events.size(); i++) {
                run.calculator.process(events.get(i));
                long fyAmount = 0;
                for (CalcResultListener.PersistedLine p : run.capture.takeAgentLines()) {
                    CommTypeCode type = p.record().commType();
                    if (type.equals(CommTypeCode.FY_COMM)) {
                        fyAmount += p.record().calcAmount().toLong();
                    } else if (type.equals(CommTypeCode.INCENTIVE)) {
                        String code = filtered.codeOf(p.line().sourceRateId());
                        long[] sum = incentiveSums.computeIfAbsent(code, k -> new long[2]);
                        sum[0] += p.record().calcAmount().toLong();
                        sum[1] += p.record().limitCutAmt().toLong();
                        incentiveIncluded.put(code, p.line().limitIncluded());
                    }
                }
                fy.add(new Installment(i + 1, fyAmount));
            }
            List<Incentive> incentiveList = new ArrayList<>();
            incentiveSums.forEach((code, sum) -> incentiveList.add(new Incentive(code, sum[0], sum[1], incentiveIncluded.get(code))));

            Optional<LimitLedger> ledger = run.ledgers.find(policyNo, agent);
            long firstYear = fy.stream().mapToLong(Installment::amount).sum() + incentiveList.stream().mapToLong(Incentive::amount).sum();
            Map<CloseYm, Long> byDue = new TreeMap<>();
            for (ScheduleEntry e : run.schedules.created()) {
                if (e.status() == ScheduleStatus.SCHEDULED) {
                    byDue.merge(e.dueYm(), e.amount().toLong(), Long::sum);
                }
            }
            List<DueSlice> schedule = new ArrayList<>();
            byDue.forEach((ym, amount) -> schedule.add(new DueSlice(ym, amount)));

            return new NewContractResult(
                    ledger.map(l -> l.limitAmount().toLong()).orElse(null),
                    fy, incentiveList,
                    ledger.map(l -> l.accumPaid().toLong()).orElse(null),
                    new Deferral(firstYear, schedule),
                    RuleSnapshotId.of(run.capture.refs()),
                    generatedAt());
        });
    }

    // ---- policy-event ----

    public PolicyEventResult simulatePolicyEvent(PolicyEventRequest req) {
        EventType type = switch (req.eventType()) {
            case "CANCEL" -> EventType.CANCEL;
            case "LAPSE" -> EventType.LAPSE;
            case "WITHDRAW" -> EventType.WITHDRAW;
            case "REDUCE" -> EventType.REDUCE;
            case "REINSTATE" -> EventType.REVIVE;
            case "PAYMENT_OVERDUE" -> throw new SimulationRejected(SimulationRejected.Kind.UNSUPPORTED_EVENT_TYPE,
                    "엔진은 PAYMENT_OVERDUE를 시뮬레이션하지 않는다(처리 경로 없음)");
            default -> throw new SimulationRejected(SimulationRejected.Kind.INVALID_REQUEST, "unknown eventType: " + req.eventType());
        };
        if (req.elapsedInstallments() != null && req.elapsedInstallments() < 1) {
            throw new SimulationRejected(SimulationRejected.Kind.INVALID_REQUEST, "elapsedInstallments must be >= 1");
        }
        if (type == EventType.REDUCE && req.newMonthlyPremium() == null) {
            throw new SimulationRejected(SimulationRejected.Kind.INVALID_REQUEST, "REDUCE requires newMonthlyPremium");
        }
        if (req.newMonthlyPremium() != null && req.newMonthlyPremium() < 0) {
            throw new SimulationRejected(SimulationRejected.Kind.INVALID_REQUEST, "newMonthlyPremium must be >= 0");
        }
        return tx.read(() -> {
            requireAgent(req.agentId());
            AgentId agent = new AgentId(req.agentId());
            PolicyNo policyNo = new PolicyNo(req.policyNo());
            List<CommCalcRecord> mine = reads.calcsByPolicy().apply(policyNo, req.agentId()).stream()
                    .filter(r -> r.recipientType() == RecipientType.AGENT).toList();
            if (mine.isEmpty()) {
                throw new SimulationRejected(SimulationRejected.Kind.POLICY_NOT_FOUND, "그 설계사의 계약이 아니다: " + req.policyNo());
            }
            // 계약의 첫 이벤트(가장 이른 eventId)에서 보험사·상품·체결일·월납 보험료
            long firstEventId = mine.stream().mapToLong(CommCalcRecord::eventId).min().orElseThrow();
            PolicyEvent contract = reads.eventById().apply(firstEventId)
                    .orElseThrow(() -> new IllegalStateException("계산 레코드의 원 이벤트가 없다: eventId=" + firstEventId));

            Map<String, String> attributes = type == EventType.REDUCE
                    ? Map.of(PremiumRepriceStep.ATTR_NEW_MONTHLY_PREMIUM, String.valueOf(req.newMonthlyPremium()))
                    : Map.of();
            PolicyEvent event = new PolicyEvent(null, "SIM:" + type, policyNo, contract.insurerCd(), contract.productKey(), type,
                    req.asOfDate(), contract.contractDate(), agent, contract.monthlyPremium(), null, req.elapsedInstallments(), attributes);

            Run run = new Run(incentives);
            run.calculator.process(event);
            long clawback = run.capture.takeAgentLines().stream()
                    .filter(p -> p.record().commType().equals(CommTypeCode.CLAWBACK))
                    .mapToLong(p -> p.record().calcAmount().toLong()).sum();

            // 귀속월의 세전·채권 상계 전 순액(gross + clawbackOffset) — ①의 산출기를 원 저장소와 오버레이에 각각
            CloseYm ym = closeYmResolver.resolveFor(event);
            long before = grossNet(new StatementEstimator(estimateReads, taxPolicy), ym, req.agentId());
            EstimateReads overlayReads = new EstimateReads(run.calcs::findByCloseYm, run.calcs::findById, run.schedules::findDue,
                    estimateReads.policyStatus(), estimateReads.offsettable(), estimateReads.adjustmentsOf(), estimateReads.closeStateOf());
            long after = grossNet(new StatementEstimator(overlayReads, taxPolicy), ym, req.agentId());

            long open = estimateReads.offsettable().apply(agent).stream().mapToLong(r -> r.remaining().toLong()).sum();
            long receivableRemaining = open + Math.max(0, -after);

            List<CancelledDeferral> cancelled = run.schedules.cancelledStored().stream()
                    .map(e -> new CancelledDeferral(String.valueOf(e.scheduleId()), e.dueYm(), e.amount().toLong())).toList();
            return new PolicyEventResult(clawback, new MonthEffect(ym, before, after), receivableRemaining, cancelled,
                    RuleSnapshotId.of(run.capture.refs()));
        });
    }

    // ---- 내부 ----

    private void requireAgent(String agentId) {
        if (!reads.agentExists().test(agentId)) {
            throw new SimulationRejected(SimulationRejected.Kind.AGENT_NOT_FOUND, "엔진에 없는 설계사: " + agentId);
        }
    }

    private InsurerCode soleInsurer(ProductKey product, NewContractRequest req) {
        List<InsurerCode> found = rules.insurersWithRate(Direction.OUTBOUND, product, CommTypeCode.FY_COMM, req.contractDate());
        if (found.size() != 1) {
            throw new SimulationRejected(SimulationRejected.Kind.INSURER_AMBIGUOUS,
                    "insurerCd가 없고 기준일 " + req.contractDate() + "에 상품 " + product + "의 유효 요율 보험사가 " + found.size() + "곳이다");
        }
        return found.getFirst();
    }

    private static long grossNet(StatementEstimator estimator, CloseYm ym, String agentId) {
        return estimator.estimate(ym, agentId).map(e -> e.math().grossNet().toLong()).orElse(0L);
    }

    private String generatedAt() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** 시뮬레이션 1회 — 오버레이 저장소 넷과 그 위의 계산기(운영과 같은 Step·훅 + 결과 수집 훅). */
    private final class Run {
        final OverlayCommCalcStore calcs = new OverlayCommCalcStore(reads);
        final OverlayLimitLedgerStore ledgers = new OverlayLimitLedgerStore(reads);
        final OverlayDeferralScheduleStore schedules = new OverlayDeferralScheduleStore(reads);
        final Capture capture = new Capture();
        final CommissionCalculator calculator;

        Run(IncentiveRepository incentiveRepository) {
            List<CalcResultListener> listeners = new ArrayList<>(CalcSteps.listeners(ledgers, schedules,
                    new DeferralReleaseService(schedules, calcs, policyStatus)));
            listeners.add(capture);
            calculator = new CommissionCalculator(rules, recipients,
                    new CalculationPipeline(CalcSteps.standard(incentiveRepository, calcs, ledgers)),
                    new OverlayPolicyEventStore(), calcs, closeYmResolver, listeners);
        }
    }

    /** 결과 수집 훅 — 사용한 룰 버전(전 수급자)과 설계사 본인의 저장 라인. */
    private static final class Capture implements CalcResultListener {
        private final List<RuleVersionRef> refs = new ArrayList<>();
        private final List<PersistedLine> agentLines = new ArrayList<>();

        @Override
        public void onPersisted(CalcContext ctx, List<PersistedLine> lines) {
            refs.addAll(ctx.rules().usedRuleVersions());
            if (ctx.target().isAgent()) {
                agentLines.addAll(lines);
            }
        }

        List<RuleVersionRef> refs() {
            return List.copyOf(refs);
        }

        List<PersistedLine> takeAgentLines() {
            List<PersistedLine> out = List.copyOf(agentLines);
            agentLines.clear();
            return out;
        }
    }

    /** {@code incentiveCodes}가 오면 그 코드의 시책만(포털 계약 v1.3 R3), 그리고 시책 ID → 코드(결과의 {@code code}). */
    private static final class FilteredIncentives implements IncentiveRepository {
        private final IncentiveRepository delegate;
        private final List<String> codes;
        private final Map<Long, String> codeById = new HashMap<>();

        FilteredIncentives(IncentiveRepository delegate, List<String> codes) {
            this.delegate = delegate;
            this.codes = codes;
        }

        @Override
        public List<IncentiveRule> findActiveAt(java.time.LocalDate baseDate) {
            List<IncentiveRule> out = delegate.findActiveAt(baseDate).stream()
                    .filter(r -> codes == null || codes.contains(r.incentiveCd()))
                    .toList();
            out.forEach(r -> codeById.put(r.incentiveId(), r.incentiveCd()));
            return out;
        }

        String codeOf(Long incentiveId) {
            String code = incentiveId == null ? null : codeById.get(incentiveId);
            if (code == null) {
                throw new IllegalStateException("시책 라인의 시책 코드를 알 수 없다: sourceRateId=" + incentiveId);
            }
            return code;
        }
    }
}
