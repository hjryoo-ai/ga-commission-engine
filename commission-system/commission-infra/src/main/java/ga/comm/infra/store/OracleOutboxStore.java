package ga.comm.infra.store;

import ga.comm.infra.mapper.OutboxMapper;
import ga.comm.outbox.OutboxEvent;
import ga.comm.outbox.OutboxStore;
import ga.comm.outbox.StoredEvent;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * OUTBOX_EVENT Oracle 어댑터(설계서 §6.8, E1 D-1·D-2). 부여는 {@code OUTBOX_SEQ} 행 잠금 아래 미부여 행을 적재 순서로 골라
 * last_seq + 1부터 붙이고 last_seq를 옮긴다 — 같은 트랜잭션에서 끝난다(호출자가 단독 트랜잭션으로 감싼다).
 */
public class OracleOutboxStore implements OutboxStore {

    private final OutboxMapper mapper;

    public OracleOutboxStore(OutboxMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public void append(OutboxEvent event) {
        OutboxMapper.Row row = new OutboxMapper.Row();
        row.eventId = event.eventId().toString();
        row.eventType = event.type();
        row.schemaVer = event.version();
        row.aggregateKind = event.aggregateKind();
        row.aggregateId = event.aggregateId();
        row.occurredAt = event.occurredAt();
        row.payload = event.payloadJson();
        mapper.insert(row);
    }

    @Override
    public int assignPending(int max) {
        long last = Objects.requireNonNull(mapper.lockLastSeq(), "OUTBOX_SEQ 행이 없다(V105)");
        List<Long> pending = mapper.pendingEventNos(max);
        for (Long eventNo : pending) {
            if (mapper.assign(eventNo, ++last) != 1) {
                throw new IllegalStateException("순번 부여 경합 — 다른 부여기가 같은 행에 붙였다(잠금 없이 부여됨): event_no=" + eventNo);
            }
        }
        if (!pending.isEmpty()) {
            mapper.advance(last);
        }
        return pending.size();
    }

    @Override
    public List<StoredEvent> readAssignedAfter(long afterSeq, int limit) {
        return mapper.assignedAfter(afterSeq, limit).stream().map(OracleOutboxStore::toStored).toList();
    }

    @Override
    public long headSeq() {
        Long last = mapper.lastSeq();
        return last == null ? 0 : last;
    }

    @Override
    public boolean contains(String type, String aggregateKind, String aggregateId) {
        return mapper.count(type, aggregateKind, aggregateId) > 0;
    }

    private static StoredEvent toStored(OutboxMapper.Row r) {
        return new StoredEvent(r.eventNo, r.seq, new OutboxEvent(UUID.fromString(r.eventId), r.eventType, r.schemaVer,
                r.occurredAt, r.aggregateKind, r.aggregateId, r.payload));
    }
}
