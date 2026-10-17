package ga.comm.settlement.simulation;

import ga.comm.calc.store.PolicyEventStore;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.type.ProcessStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 정책 이벤트 오버레이(E2 ②) — 가상 이벤트만 메모리에 둔다(음수 ID). 원 저장소에는 닿지 않는다: 멱등 판정도 이 시뮬레이션 안에서만이고,
 * 계약의 원 이벤트 조회는 시뮬레이터가 {@link SimulationReads#eventById()}로 한다.
 */
public final class OverlayPolicyEventStore implements PolicyEventStore {

    private final Map<String, PolicyEvent> byKey = new LinkedHashMap<>();
    private final Map<Long, ProcessStatus> status = new LinkedHashMap<>();
    private long nextId = 0;

    @Override
    public UpsertResult upsertByKey(PolicyEvent event) {
        PolicyEvent existing = byKey.get(event.eventKey());
        if (existing != null) {
            return new UpsertResult(existing, true, status.get(existing.eventId()));
        }
        PolicyEvent stored = event.withEventId(--nextId);
        byKey.put(event.eventKey(), stored);
        status.put(stored.eventId(), ProcessStatus.PENDING);
        return new UpsertResult(stored, false, ProcessStatus.PENDING);
    }

    @Override
    public Optional<PolicyEvent> findById(long eventId) {
        return byKey.values().stream().filter(e -> e.eventId() == eventId).findFirst();
    }

    @Override
    public void markProcessed(long eventId) {
        status.put(eventId, ProcessStatus.PROCESSED);
    }

    @Override
    public void markFailed(long eventId, String reason) {
        status.put(eventId, ProcessStatus.FAILED);
    }

    @Override
    public ProcessStatus statusOf(long eventId) {
        return status.getOrDefault(eventId, ProcessStatus.PENDING);
    }

    @Override
    public long unprocessedCount() {
        return status.values().stream().filter(s -> s != ProcessStatus.PROCESSED).count();
    }
}
