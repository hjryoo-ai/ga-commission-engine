package ga.comm.outbox.fixture;

import ga.comm.outbox.OutboxEvent;
import ga.comm.outbox.OutboxStore;
import ga.comm.outbox.StoredEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 인메모리 레퍼런스 아웃박스(설계서 §8.7 — Oracle 어댑터와 같은 계약 스위트를 상속). 트랜잭션이 없으므로 적재한 행은 곧 "커밋된" 행이다.
 * 동시 부여는 {@code synchronized}로 직렬화한다(Oracle의 {@code OUTBOX_SEQ FOR UPDATE}에 해당).
 */
public class InMemoryOutboxStore implements OutboxStore {

    private final List<StoredEvent> rows = new ArrayList<>();
    private long head;

    @Override
    public synchronized void append(OutboxEvent event) {
        if (rows.stream().anyMatch(r -> r.event().eventId().equals(event.eventId()))) {
            throw new IllegalStateException("eventId 중복: " + event.eventId());
        }
        rows.add(new StoredEvent(rows.size() + 1L, null, event));
    }

    @Override
    public synchronized int assignPending(int max) {
        int assigned = 0;
        for (int i = 0; i < rows.size() && assigned < max; i++) {
            StoredEvent r = rows.get(i);
            if (r.seq() == null) {
                rows.set(i, new StoredEvent(r.eventNo(), ++head, r.event()));
                assigned++;
            }
        }
        return assigned;
    }

    @Override
    public synchronized List<StoredEvent> readAssignedAfter(long afterSeq, int limit) {
        return rows.stream()
                .filter(r -> r.seq() != null && r.seq() > afterSeq)
                .sorted((a, b) -> Long.compare(a.seq(), b.seq()))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized long headSeq() {
        return head;
    }

    @Override
    public synchronized boolean contains(String type, String aggregateKind, String aggregateId) {
        return rows.stream().anyMatch(r -> r.event().type().equals(type)
                && r.event().aggregateKind().equals(aggregateKind) && r.event().aggregateId().equals(aggregateId));
    }

    /** 시험용 — 적재 순서대로 전부(부여 여부 무관). */
    public synchronized List<StoredEvent> all() {
        return List.copyOf(rows);
    }
}
