package ga.comm.outbox.publish;

import ga.comm.calc.store.CommCalcRecord;
import ga.comm.domain.type.RecipientType;
import ga.comm.settlement.PayoutListener;
import ga.comm.settlement.PayoutService;
import ga.comm.settlement.SettlementRow;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 수급자 1명 정산 → {@code PayoutCompleted}(D-6, E1 P4·P5) — 지급 항목 트랜잭션 안, 지급명세 저장 뒤. AGENT 수급자만(D-3).
 * {@code occurredAt} = {@code paidAt} = {@code PAYOUT_STATEMENT.paid_at}. 집합은 {@code {agentId}:{closeYm}} — 같은 달 두 번째 런도 같은 집합이다.
 */
public final class PayoutPublisher implements PayoutListener {

    public static final String TYPE = "PayoutCompleted";
    public static final String KIND = "PAYOUT";

    private final OutboxPublisher publisher;

    public PayoutPublisher(OutboxPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher);
    }

    @Override
    public void onSettled(PayoutService.PayoutStatement statement, SettlementRow settlement, List<CommCalcRecord> records,
                          OffsetDateTime paidAt) {
        if (statement.recipientType() != RecipientType.AGENT) {
            return;
        }
        publisher.publish(TYPE, KIND, PortalPayloads.statementAggregateId(statement.recipientId(), statement.closeYm()),
                PortalPayloads.payoutCompleted(statement, settlement, records, paidAt), paidAt);
    }
}
