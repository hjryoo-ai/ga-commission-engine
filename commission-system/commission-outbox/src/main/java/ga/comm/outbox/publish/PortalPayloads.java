package ga.comm.outbox.publish;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ga.comm.calc.net.NetAmountCalculator;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.clawback.ClawbackReceivable;
import ga.comm.deferral.DeferralTransitionReason;
import ga.comm.deferral.ScheduleEntry;
import ga.comm.deferral.ScheduleStatus;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.EventType;
import ga.comm.domain.type.RecipientType;
import ga.comm.limit.LimitLedger;
import ga.comm.settlement.AdjustmentService;
import ga.comm.settlement.PayoutService;
import ga.comm.settlement.SettlementRow;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 엔진 사실 → 포털 계약 payload(포털 {@code contracts/shared/ga-agent-portal/events/v1/payloads/*}, 계약 v1.1.1) — 빌더는 이 클래스 한 곳이다.
 * 금액은 정수 원({@link Money#toLong()}), 소수는 불투명 문자열({@link Decimals}), 날짜는 ISO {@code YYYY-MM-DD}, 마감월은 {@code yyyyMM}.
 * 엔진에 없는 값은 지어내지 않는다(계약 v1.1 — 선택 필드는 있을 때만).
 *
 * <p>집계 금액의 정의(계약 v1.1 K7·포털 DECISIONS CI-1, E1 P4): {@code gross} = Σ CLAWBACK이 아닌 라인, {@code clawbackOffset} = Σ CLAWBACK
 * 라인 — <b>전 상태 합산</b>(상태 필터 금지 §3.0). 합산은 {@link NetAmountCalculator} 경유(유형 분리만 빌더가 한다). 엔진 {@code COMM_CALC}에는
 * ADJUSTMENT 유형이 없다(정정은 {@code ADJUSTMENT} 표 — {@code AdjustmentPosted}, E1 P5).
 */
public final class PortalPayloads {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** 엔진 이벤트 유형 → 포털 {@code PolicyEventObserved.eventType}(D-7). 없는 유형(NEW·PAYMENT·CORRECT)은 발행하지 않는다. */
    public static final Map<EventType, String> POLICY_EVENT_TYPES;

    static {
        Map<EventType, String> m = new EnumMap<>(EventType.class);
        m.put(EventType.CANCEL, "CANCEL");
        m.put(EventType.LAPSE, "LAPSE");
        m.put(EventType.WITHDRAW, "WITHDRAW");
        m.put(EventType.REDUCE, "REDUCE");
        m.put(EventType.REVIVE, "REINSTATE");
        POLICY_EVENT_TYPES = java.util.Collections.unmodifiableMap(m);
    }

    private PortalPayloads() {
    }

    // ------------------------------------------------------------------ CommissionCalculated (D-4)

    /**
     * {@code amount} = {@code calc_amount}(한도 삭감 뒤·분급 분할 뒤 지급 예정액), {@code limitCutAmt} = {@code limit_cut_amt}, 요청액 = 둘의 합.
     * {@code limitCutPolicy} = 게이트가 기록한 룰의 {@code over_limit_action}(삭감이 있을 때만).
     */
    public static ObjectNode commissionCalculated(CommCalcRecord r, PolicyEvent event) {
        if (r.recipientType() != RecipientType.AGENT) {
            throw new IllegalArgumentException("ORG 수급자는 발행하지 않는다(D-3): calcId=" + r.calcId());
        }
        ObjectNode p = OutboxPublisher.payload();
        p.put("calcId", id(r.calcId()));
        p.put("agentId", r.recipientId());
        p.put("policyNo", r.policyNo().value());
        p.put("productKey", event.productKey().value());
        p.put("commType", r.commType().value());
        p.put("amount", r.calcAmount().toLong());
        p.put("closeYm", r.closeYm().value());
        if (r.reversalOf() != null) {
            p.put("reversalOfCalcId", id(r.reversalOf()));
        }
        p.put("limitCutAmt", r.limitCutAmt().toLong());
        if (r.limitCutAmt().isPositive()) {
            traceValue(r.calcTrace(), "LIMIT_GATE_V1", "action", r.commType().value())
                    .ifPresent(action -> p.put("limitCutPolicy", action));
        }
        ObjectNode basis = p.putObject("basis");
        if (r.appliedRate() != null) {
            basis.put("rate", Decimals.plain(r.appliedRate().value()));
        }
        traceValue(r.calcTrace(), "PAYOUT_RATE_V1", "payoutRate", r.commType().value())
                .ifPresent(rate -> basis.put("payoutRate", Decimals.plain(rate)));
        if (event.installmentNo() != null) {
            basis.put("installmentNo", event.installmentNo());
        }
        ArrayNode ids = basis.putArray("ruleVersionIds");
        ruleVersionIds(r.ruleVersions()).forEach(ids::add);
        ObjectNode summary = p.putObject("policySummary");
        summary.put("contractDate", event.contractDate().toString());
        summary.put("monthlyPremium", event.monthlyPremium().toLong());
        return p;
    }

    /** {@code rule_versions} JSON → {@code type:key:ref} 식별자(포털 defs {@code id} — 1~64자). */
    static List<String> ruleVersionIds(String ruleVersions) {
        List<String> out = new ArrayList<>();
        if (ruleVersions == null || ruleVersions.isBlank()) {
            return out;
        }
        for (JsonNode n : parse(ruleVersions)) {
            String id = n.path("type").asText() + ":" + n.path("key").asText() + ":" + n.path("ref").asText();
            if (id.length() > 64) {
                throw new IllegalStateException("룰 버전 식별자가 계약 길이(64)를 넘는다: " + id);
            }
            out.add(id);
        }
        return out;
    }

    /** {@code calc_trace}에서 {@code step}의 값 하나 — {@code commType}이 주어지면 그 유형의 항목만(수급자 하나의 트레이스는 라인이 공유한다). */
    static Optional<String> traceValue(String calcTrace, String step, String key, String commType) {
        if (calcTrace == null || calcTrace.isBlank()) {
            return Optional.empty();
        }
        for (JsonNode e : parse(calcTrace)) {
            JsonNode values = e.path("values");
            if (step.equals(e.path("step").asText()) && values.has(key)
                    && (commType == null || commType.equals(values.path("commType").asText()))) {
                return Optional.of(values.path(key).asText());
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ PolicyEventObserved (D-7)

    public static Optional<ObjectNode> policyEventObserved(PolicyEvent e) {
        String type = POLICY_EVENT_TYPES.get(e.eventType());
        if (type == null) {
            return Optional.empty();
        }
        ObjectNode p = OutboxPublisher.payload();
        p.put("policyNo", e.policyNo().value());
        p.put("agentId", e.agentId().value());
        p.put("eventType", type);
        p.put("occurredOn", e.eventDate().toString());
        return Optional.of(p);
    }

    // ------------------------------------------------------------------ 한도 원장 (D-9)

    public static ObjectNode limitLedgerOpened(LimitLedger l) {
        ObjectNode p = OutboxPublisher.payload();
        p.put("ledgerId", id(l.ledgerId()));
        p.put("policyNo", l.policyNo().value());
        p.put("agentId", l.agentId().value());
        p.put("contractDate", l.contractDate().toString());
        p.put("fyStart", l.fyStart().toString());
        p.put("fyEnd", l.fyEnd().toString());
        p.put("monthlyPremium", l.monthlyPremium().toLong());
        p.put("limitMultiple", Decimals.plain(l.limitMultiple()));
        p.put("limitAmount", l.limitAmount().toLong());
        p.put("ruleVersionId", id(l.ruleVersionId()));
        return p;
    }

    public static ObjectNode limitLedgerPosted(LimitLedger l, int postingSeq, LimitLedger.Posting posting, Money accumAfter) {
        ObjectNode p = OutboxPublisher.payload();
        p.put("ledgerId", id(l.ledgerId()));
        p.put("postingSeq", postingSeq);
        p.put("calcId", id(posting.calcId()));
        p.put("amount", posting.amount().toLong());
        p.put("accumAfter", accumAfter.toLong());
        return p;
    }

    /** 재산정 사유 — 닫힌 어휘(E1 승인 Q2). */
    public enum RebaseReason { PREMIUM_REDUCED, PREMIUM_INCREASED }

    public static ObjectNode limitLedgerRebased(LimitLedger l, Money oldLimit, int lastPostingSeq) {
        ObjectNode p = OutboxPublisher.payload();
        p.put("ledgerId", id(l.ledgerId()));
        if (lastPostingSeq > 0) {
            p.put("postingSeq", lastPostingSeq);   // 계약 v1.1.1 L1 — 전기가 없던 원장이면 생략
        }
        p.put("oldLimit", oldLimit.toLong());
        p.put("newLimit", l.limitAmount().toLong());
        p.put("monthlyPremium", l.monthlyPremium().toLong());
        p.put("reason", (l.limitAmount().isLessThan(oldLimit) ? RebaseReason.PREMIUM_REDUCED : RebaseReason.PREMIUM_INCREASED).name());
        return p;
    }

    // ------------------------------------------------------------------ 분급 (D-10)

    /** 엔진이 만드는 스케줄은 FY 커브 분할뿐이다 — reason {@code CURVE}(E1 P1: 한도 이연은 스케줄을 만들지 않는다). */
    public static ObjectNode deferralScheduled(ScheduleEntry e) {
        ObjectNode p = OutboxPublisher.payload();
        p.put("scheduleId", id(e.scheduleId()));
        p.put("sourceCalcId", id(e.sourceCalcId()));
        p.put("agentId", e.agentId().value());
        p.put("policyNo", e.policyNo().value());
        p.put("dueYm", e.dueYm().value());
        p.put("amount", e.amount().toLong());
        p.put("payCondition", e.payCondition());
        p.put("curveVersionId", id(e.curveVersionId()));
        p.put("reason", "CURVE");
        return p;
    }

    public static ObjectNode deferralStatusChanged(ScheduleEntry to, ScheduleStatus from, DeferralTransitionReason reason) {
        ObjectNode p = OutboxPublisher.payload();
        p.put("scheduleId", id(to.scheduleId()));
        p.put("from", from.name());
        p.put("to", to.status().name());
        p.put("reason", reason.name());
        return p;
    }

    // ------------------------------------------------------------------ 채권 (D-11)

    public static ObjectNode clawbackReceivableUpdated(ClawbackReceivable r) {
        if (r.originCalcId() == null) {
            throw new IllegalStateException("채권의 원인 계산이 없다 — 포털 계약은 originCalcId를 요구한다: receivableId=" + r.receivableId());
        }
        ObjectNode p = OutboxPublisher.payload();
        p.put("receivableId", id(r.receivableId()));
        p.put("agentId", r.agentId().value());
        p.put("originCalcId", id(r.originCalcId()));
        p.put("amount", r.amount().toLong());
        p.put("remaining", r.remaining().toLong());
        p.put("status", r.status().name());
        return p;
    }

    // ------------------------------------------------------------------ 정정 (D-12 — AdjustmentPosted만)

    public static ObjectNode adjustmentPosted(AdjustmentService.Adjustment a, String agentId) {
        if (a.targetCalcId() == null) {
            throw new IllegalStateException("정정 대상 계산이 없다 — 포털 계약은 targetCalcId를 요구한다: adjId=" + a.adjId());
        }
        ObjectNode p = OutboxPublisher.payload();
        p.put("adjId", id(a.adjId()));
        p.put("targetCalcId", id(a.targetCalcId()));
        p.put("agentId", agentId);
        p.put("amount", a.amount().toLong());
        p.put("closeYm", a.closeYm().value());
        p.put("reason", a.reason());
        return p;
    }

    // ------------------------------------------------------------------ 마감·지급 (D-5·D-6, P4·P5)

    /** 레코드를 CLAWBACK과 그 밖으로 나눈 두 합 — {@link NetAmountCalculator} 경유, 상태 필터 없음. */
    public record Totals(Money gross, Money clawbackOffset) {
        public Money net() {
            return gross.plus(clawbackOffset);
        }
    }

    public static Totals totals(List<CommCalcRecord> records) {
        List<CommCalcRecord> clawbacks = records.stream().filter(r -> r.commType().equals(CommTypeCode.CLAWBACK)).toList();
        List<CommCalcRecord> others = records.stream().filter(r -> !r.commType().equals(CommTypeCode.CLAWBACK)).toList();
        return new Totals(NetAmountCalculator.netOf(others), NetAmountCalculator.netOf(clawbacks));
    }

    /** 마감 시점에 참인 것만 — gross·clawbackOffset·adjustment·closedAt. 세액·순액은 지급 런의 일이라 넣지 않는다(계약 v1.1 K4). */
    public static ObjectNode statementConfirmed(String agentId, CloseYm ym, List<CommCalcRecord> agentRecords, Money adjustment,
                                                OffsetDateTime closedAt) {
        Totals t = totals(agentRecords);
        ObjectNode p = OutboxPublisher.payload();
        p.put("agentId", agentId);
        p.put("closeYm", ym.value());
        p.put("gross", t.gross().toLong());
        p.put("clawbackOffset", t.clawbackOffset().toLong());
        p.put("adjustment", adjustment.toLong());
        p.put("closedAt", OutboxPublisher.instant(closedAt));
        return p;
    }

    /**
     * 지급 런의 확정값(계약 v1.1 K5). {@code gross + clawbackOffset}은 정산 행의 {@code gross_net}(전 레코드 순합)과 같아야 한다 —
     * 두 정의(포털 합산·엔진 순액)가 어긋나면 발행 시점에 멈춘다(E1 P4). {@code adjustment}는 넣지 않는다 — 정정은 지급 순액에 들어 있지
     * 않다(E1 P5, 엔진 §11).
     */
    public static ObjectNode payoutCompleted(PayoutService.PayoutStatement s, SettlementRow row, List<CommCalcRecord> records,
                                             OffsetDateTime paidAt) {
        Totals t = totals(records);
        if (!t.net().equals(row.grossNet())) {
            throw new IllegalStateException("지급 이벤트의 gross + clawbackOffset(" + t.net() + ") ≠ 정산 행 gross_net(" + row.grossNet()
                    + ") — 합산 정의가 어긋났다: " + s.recipientId() + "/" + s.closeYm());
        }
        Money withholding = s.incomeTax().plus(s.localTax());
        ObjectNode p = OutboxPublisher.payload();
        p.put("agentId", s.recipientId());
        p.put("closeYm", s.closeYm().value());
        p.put("paidAt", OutboxPublisher.instant(paidAt));
        p.put("paidAmount", s.netPay().toLong());
        p.put("runSeq", s.runSeq());
        p.put("gross", t.gross().toLong());
        p.put("clawbackOffset", t.clawbackOffset().toLong());
        p.put("receivableOffset", row.receivableOffset().toLong());
        p.put("carriedReceivable", row.carriedReceivable().toLong());
        p.put("payable", s.payable().toLong());
        p.put("withholdingTax", withholding.toLong());
        p.put("net", s.netPay().toLong());
        return p;
    }

    // ------------------------------------------------------------------ 공통

    public static String statementAggregateId(String agentId, CloseYm ym) {
        return agentId + ":" + ym.value();
    }

    private static String id(Long v) {
        if (v == null) {
            throw new IllegalStateException("식별자가 없다(저장 전 객체를 발행하려 했다)");
        }
        return Long.toString(v);
    }

    private static String id(long v) {
        return Long.toString(v);
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("엔진 JSON(rule_versions/calc_trace)을 읽을 수 없다", e);
        }
    }
}
