package ga.comm.outbox.publish;

import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.RecipientType;
import ga.comm.settlement.AdjustmentService;
import ga.comm.settlement.MonthCloseListener;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 마감 확정 → 설계사마다 {@code StatementConfirmed}(D-5, E1 P4·P5) — 마감 트랜잭션 안. 그 달 AGENT 레코드(상태 무관)를 설계사별로 묶어
 * {@code gross}·{@code clawbackOffset}을, 그 달 귀속 정정({@code ADJUSTMENT}, 대상 계산의 수급자)의 합을 {@code adjustment}로 낸다.
 * 레코드가 없고 정정만 있는 설계사도 한 건을 받는다(정정은 그 달 명세의 사실). ORG 레코드·ORG 대상 정정은 제외(D-3).
 * 설계사 순서는 ID 순 — 같은 입력이면 같은 이벤트 순서(골든).
 */
public final class StatementPublisher implements MonthCloseListener {

    public static final String TYPE = "StatementConfirmed";
    public static final String KIND = "STATEMENT";

    private final AdjustmentService.AdjustmentStore adjustments;
    private final CommCalcStore calcStore;
    private final OutboxPublisher publisher;

    public StatementPublisher(AdjustmentService.AdjustmentStore adjustments, CommCalcStore calcStore, OutboxPublisher publisher) {
        this.adjustments = Objects.requireNonNull(adjustments);
        this.calcStore = Objects.requireNonNull(calcStore);
        this.publisher = Objects.requireNonNull(publisher);
    }

    @Override
    public void onConfirmed(CloseYm closeYm, OffsetDateTime closedAt, List<CommCalcRecord> records) {
        Map<String, List<CommCalcRecord>> byAgent = new TreeMap<>();
        for (CommCalcRecord r : records) {
            if (r.recipientType() == RecipientType.AGENT) {
                byAgent.computeIfAbsent(r.recipientId(), k -> new ArrayList<>()).add(r);
            }
        }
        Map<String, Money> adjustmentByAgent = new LinkedHashMap<>();
        for (AdjustmentService.Adjustment a : adjustments.findByCloseYm(closeYm)) {
            if (a.targetCalcId() == null) {
                continue;   // 대상 없는 정정은 설계사에 귀속할 수 없다 — AdjustmentPosted도 없다(OutboxAdjustmentStore가 거부)
            }
            CommCalcRecord target = calcStore.findById(a.targetCalcId()).orElseThrow(() -> new IllegalStateException(
                    "정정 대상 계산이 없다: adjId=" + a.adjId()));
            if (target.recipientType() == RecipientType.AGENT) {
                adjustmentByAgent.merge(target.recipientId(), a.amount(), Money::plus);
                byAgent.computeIfAbsent(target.recipientId(), k -> new ArrayList<>());
            }
        }
        for (Map.Entry<String, List<CommCalcRecord>> e : byAgent.entrySet()) {
            String agentId = e.getKey();
            publisher.publish(TYPE, KIND, PortalPayloads.statementAggregateId(agentId, closeYm),
                    PortalPayloads.statementConfirmed(agentId, closeYm, e.getValue(),
                            adjustmentByAgent.getOrDefault(agentId, Money.ZERO), closedAt),
                    closedAt);
        }
    }
}
