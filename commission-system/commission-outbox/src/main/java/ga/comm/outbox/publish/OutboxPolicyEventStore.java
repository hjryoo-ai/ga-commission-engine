package ga.comm.outbox.publish;

import ga.comm.calc.store.PolicyEventStore;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.type.ProcessStatus;

import java.util.Objects;
import java.util.Optional;

/**
 * 정책 이벤트를 새로 넣었을 때 {@code PolicyEventObserved}(D-7) — 중복 수신(같은 키)은 내지 않는다. 유형 매핑은
 * {@link PortalPayloads#POLICY_EVENT_TYPES} 한 곳(REVIVE→REINSTATE, NEW·PAYMENT·CORRECT는 발행 없음). 집합은 증권({@code POLICY}).
 */
public final class OutboxPolicyEventStore implements PolicyEventStore {

    public static final String TYPE = "PolicyEventObserved";
    public static final String KIND = "POLICY";

    private final PolicyEventStore delegate;
    private final OutboxPublisher publisher;

    public OutboxPolicyEventStore(PolicyEventStore delegate, OutboxPublisher publisher) {
        this.delegate = Objects.requireNonNull(delegate);
        this.publisher = Objects.requireNonNull(publisher);
    }

    @Override
    public UpsertResult upsertByKey(PolicyEvent event) {
        UpsertResult result = delegate.upsertByKey(event);
        if (!result.duplicate()) {
            PortalPayloads.policyEventObserved(result.event()).ifPresent(p ->
                    publisher.publish(TYPE, KIND, result.event().policyNo().value(), p));
        }
        return result;
    }

    @Override
    public Optional<PolicyEvent> findById(long eventId) {
        return delegate.findById(eventId);
    }

    @Override
    public void markProcessed(long eventId) {
        delegate.markProcessed(eventId);
    }

    @Override
    public void markFailed(long eventId, String reason) {
        delegate.markFailed(eventId, reason);
    }

    @Override
    public ProcessStatus statusOf(long eventId) {
        return delegate.statusOf(eventId);
    }

    @Override
    public long unprocessedCount() {
        return delegate.unprocessedCount();
    }
}
