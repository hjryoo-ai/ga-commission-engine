package ga.comm.outbox.publish;

import ga.comm.clawback.ClawbackReceivable;
import ga.comm.clawback.ClawbackReceivableStore;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.money.Money;

import java.util.List;
import java.util.Objects;

/**
 * 환수 채권의 생성·변경마다 {@code ClawbackReceivableUpdated}(D-11) — 지급 런의 채권화({@code create})와 상계({@code save}).
 * 상계 내역 행({@code recordOffset})은 채권 잔액 변경과 같은 상계의 기록이라 따로 내지 않는다.
 */
public final class OutboxClawbackReceivableStore implements ClawbackReceivableStore {

    public static final String TYPE = "ClawbackReceivableUpdated";
    public static final String KIND = "CLAWBACK_RECEIVABLE";

    private final ClawbackReceivableStore delegate;
    private final OutboxPublisher publisher;

    public OutboxClawbackReceivableStore(ClawbackReceivableStore delegate, OutboxPublisher publisher) {
        this.delegate = Objects.requireNonNull(delegate);
        this.publisher = Objects.requireNonNull(publisher);
    }

    @Override
    public ClawbackReceivable create(AgentId agentId, Long originCalcId, Money amount) {
        ClawbackReceivable created = delegate.create(agentId, originCalcId, amount);
        publish(created);
        return created;
    }

    @Override
    public List<ClawbackReceivable> findOffsettable(AgentId agentId) {
        return delegate.findOffsettable(agentId);
    }

    @Override
    public void save(ClawbackReceivable receivable) {
        delegate.save(receivable);
        publish(receivable);
    }

    @Override
    public void recordOffset(long receivableId, long calcId, Money amount) {
        delegate.recordOffset(receivableId, calcId, amount);
    }

    private void publish(ClawbackReceivable r) {
        publisher.publish(TYPE, KIND, Long.toString(r.receivableId()), PortalPayloads.clawbackReceivableUpdated(r));
    }
}
