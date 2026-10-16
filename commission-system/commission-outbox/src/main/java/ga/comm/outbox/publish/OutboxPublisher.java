package ga.comm.outbox.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ga.comm.outbox.OutboxEvent;
import ga.comm.outbox.OutboxStore;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 포털 이벤트 한 건을 아웃박스에 적재한다(Phase E1 D-14). 호출한 업무 코드의 트랜잭션 안에서 적재되므로 업무가 롤백되면 이벤트도 없다(C3).
 * {@code eventId}는 발행 시 생성(UUID), {@code occurredAt}은 사실의 시각 — 주입된 시계의 지금(초 단위) 또는 업무가 정한 시각(마감·지급).
 */
public final class OutboxPublisher {

    /** 피드에 나가는 판(포털 계약 v1 — Envelope {@code version}). */
    public static final int VERSION = 1;
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final OutboxStore store;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public OutboxPublisher(OutboxStore store, Clock clock) {
        this(store, clock, UUID::randomUUID);
    }

    public OutboxPublisher(OutboxStore store, Clock clock, Supplier<UUID> ids) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
        this.ids = Objects.requireNonNull(ids);
    }

    public static ObjectNode payload() {
        return JSON.createObjectNode();
    }

    /** 사실의 시각 = 지금(주입된 시계, 초 단위). */
    public void publish(String type, String aggregateKind, String aggregateId, ObjectNode payload) {
        publish(type, aggregateKind, aggregateId, payload, OffsetDateTime.now(clock));
    }

    public void publish(String type, String aggregateKind, String aggregateId, ObjectNode payload, OffsetDateTime occurredAt) {
        String at = occurredAt.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        store.append(new OutboxEvent(ids.get(), type, VERSION, at, aggregateKind, aggregateId, payload.toString()));
    }

    public boolean alreadyPublished(String type, String aggregateKind, String aggregateId) {
        return store.contains(type, aggregateKind, aggregateId);
    }

    public Clock clock() {
        return clock;
    }

    /** 계약 instant 형식(초 단위, 오프셋 포함). */
    public static String instant(OffsetDateTime at) {
        return at.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
