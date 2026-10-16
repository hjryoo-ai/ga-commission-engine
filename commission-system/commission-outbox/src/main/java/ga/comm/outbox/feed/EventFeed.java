package ga.comm.outbox.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ga.comm.outbox.EnvelopeJson;
import ga.comm.outbox.OutboxSequencer;
import ga.comm.outbox.OutboxStore;
import ga.comm.outbox.StoredEvent;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 이벤트 피드(설계서 §6.8, 포털 계약 {@code engine-internal.openapi.yaml} {@code GET /internal/v1/events}) — Spring 무의존.
 *
 * <p>한 호출 = 트랜잭션 둘(E1 P8): ① <b>쓰기 트랜잭션</b>에서 커밋된 미부여 행에 순번을 붙인다(최대 {@code batchSize}건 — lazy 부여, 같은 일을
 * {@code outboxSequenceJob}도 한다). ② 그 뒤 <b>읽기 전용 트랜잭션</b>에서 {@code headSeq}와 {@code seq > afterSeq}를 읽는다. 부여 트랜잭션을
 * 짧게 끝내므로 읽기가 순번 표 잠금을 오래 잡지 않는다. 미부여 행은 나오지 않는다(부여 상한 너머·부여 뒤 커밋된 행은 다음 호출에서).
 */
public final class EventFeed {

    public static final int DEFAULT_LIMIT = 200;
    public static final int MAX_LIMIT = 1000;
    /** 피드 응답의 판(계약 FeedResponse.schemaVersion). */
    public static final int SCHEMA_VERSION = 1;
    private static final Pattern TENANT = Pattern.compile("^[A-Z0-9][A-Z0-9_]{0,31}$");
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** 트랜잭션 경계(호출자가 준다 — 앱은 TransactionTemplate, 시험은 그대로 실행). */
    @FunctionalInterface
    public interface Tx {
        <T> T run(Supplier<T> work);
    }

    /** 한 번의 피드 응답. {@code nextSeq} = 마지막 이벤트의 seq(없으면 afterSeq). */
    public record Page(String tenantId, List<StoredEvent> events, long nextSeq, long headSeq) {
    }

    /** {@code afterSeq > headSeq} — 다른 발행자(복원된 DB 등)를 읽고 있다(계약 422 AFTER_BEYOND_HEAD). */
    public static final class AfterBeyondHeadException extends RuntimeException {
        public AfterBeyondHeadException(long afterSeq, long headSeq) {
            super("afterSeq " + afterSeq + " is beyond headSeq " + headSeq);
        }
    }

    private final OutboxStore store;
    private final OutboxSequencer sequencer;
    private final Tx writeTx;
    private final Tx readOnlyTx;
    private final String tenantId;

    public EventFeed(OutboxStore store, OutboxSequencer sequencer, Tx writeTx, Tx readOnlyTx, String tenantId) {
        this.store = Objects.requireNonNull(store);
        this.sequencer = Objects.requireNonNull(sequencer);
        this.writeTx = Objects.requireNonNull(writeTx);
        this.readOnlyTx = Objects.requireNonNull(readOnlyTx);
        if (tenantId == null || !TENANT.matcher(tenantId).matches()) {
            throw new IllegalStateException("피드 테넌트 ID가 없거나 계약 형식(^[A-Z0-9][A-Z0-9_]{0,31}$)이 아니다: " + tenantId);
        }
        this.tenantId = tenantId;
    }

    public Page read(long afterSeq, int limit) {
        if (afterSeq < 0) {
            throw new IllegalArgumentException("afterSeq는 0 이상: " + afterSeq);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit는 1~" + MAX_LIMIT + ": " + limit);
        }
        writeTx.run(sequencer::assignOnce);
        return readOnlyTx.run(() -> {
            long head = store.headSeq();
            if (afterSeq > head) {
                throw new AfterBeyondHeadException(afterSeq, head);
            }
            List<StoredEvent> events = store.readAssignedAfter(afterSeq, limit);
            long next = events.isEmpty() ? afterSeq : events.get(events.size() - 1).seq();
            return new Page(tenantId, events, next, head);
        });
    }

    /** 계약 FeedResponse JSON. Envelope는 저장된 값 그대로(occurredAt 문자열·payload). */
    public static ObjectNode render(Page page) {
        ObjectNode body = JSON.createObjectNode();
        body.put("tenantId", page.tenantId());
        ArrayNode events = body.putArray("events");
        for (StoredEvent e : page.events()) {
            events.add(EnvelopeJson.render(e));
        }
        body.put("nextSeq", page.nextSeq());
        body.put("headSeq", page.headSeq());
        body.put("schemaVersion", SCHEMA_VERSION);
        return body;
    }

    public String tenantId() {
        return tenantId;
    }
}
