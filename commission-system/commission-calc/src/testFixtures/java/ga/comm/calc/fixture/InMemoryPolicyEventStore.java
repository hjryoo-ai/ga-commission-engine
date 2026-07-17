package ga.comm.calc.fixture;

import ga.comm.calc.store.PolicyEventStore;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.type.ProcessStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** 인메모리 POLICY_EVENT 저장소 — event_key 멱등 저장. */
public class InMemoryPolicyEventStore implements PolicyEventStore {

    private final Map<String, PolicyEvent> byKey = new HashMap<>();
    private final Map<Long, ProcessStatus> statusById = new HashMap<>();
    private final Map<Long, String> failReasons = new HashMap<>();
    private final AtomicLong idSeq = new AtomicLong(0);

    @Override
    public synchronized UpsertResult upsertByKey(PolicyEvent event) {
        PolicyEvent existing = byKey.get(event.eventKey());
        if (existing != null) {
            return new UpsertResult(existing, true, statusById.get(existing.eventId()));
        }
        PolicyEvent withId = event.withEventId(idSeq.incrementAndGet());
        byKey.put(withId.eventKey(), withId);
        statusById.put(withId.eventId(), ProcessStatus.PENDING);
        return new UpsertResult(withId, false, ProcessStatus.PENDING);
    }

    @Override
    public synchronized Optional<PolicyEvent> findById(long eventId) {
        return byKey.values().stream()
                .filter(e -> e.eventId() != null && e.eventId() == eventId)
                .findFirst();
    }

    @Override
    public synchronized void markProcessed(long eventId) {
        statusById.put(eventId, ProcessStatus.PROCESSED);
    }

    @Override
    public synchronized void markFailed(long eventId, String reason) {
        statusById.put(eventId, ProcessStatus.FAILED);
        failReasons.put(eventId, reason);
    }

    @Override
    public synchronized long unprocessedCount() {
        return statusById.values().stream()
                .filter(s -> s == ProcessStatus.PENDING || s == ProcessStatus.FAILED)
                .count();
    }

    @Override
    public synchronized ProcessStatus statusOf(long eventId) {
        ProcessStatus status = statusById.get(eventId);
        if (status == null) {
            throw new IllegalArgumentException("존재하지 않는 eventId: " + eventId);
        }
        return status;
    }
}
