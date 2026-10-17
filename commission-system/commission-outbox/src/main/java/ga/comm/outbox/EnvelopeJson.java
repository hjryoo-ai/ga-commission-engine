package ga.comm.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 저장된 행 → 포털 Envelope JSON(설계서 §6.8, 포털 계약 {@code events/v1/envelope.schema.json}).
 * 테넌트는 Envelope에 없다(엔진은 테넌트별 인스턴스 — 피드 응답의 {@code tenantId}가 말한다).
 */
public final class EnvelopeJson {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private EnvelopeJson() {
    }

    /** 순번이 붙은 행만 Envelope가 된다(seq는 Envelope 필수). */
    public static ObjectNode render(StoredEvent stored) {
        if (stored.seq() == null) {
            throw new IllegalStateException("순번이 없는 행은 피드에 나가지 않는다: eventNo=" + stored.eventNo());
        }
        return render(stored.event(), stored.seq());
    }

    /** 순번을 지정해 Envelope를 만든다(적재 시 검증 — 시험 하네스). */
    public static ObjectNode render(OutboxEvent e, long seq) {
        ObjectNode env = JSON.createObjectNode();
        env.put("eventId", e.eventId().toString());
        env.put("seq", seq);
        env.put("type", e.type());
        env.put("version", e.version());
        env.put("occurredAt", e.occurredAt());
        ObjectNode aggregate = env.putObject("aggregate");
        aggregate.put("kind", e.aggregateKind());
        aggregate.put("id", e.aggregateId());
        env.set("payload", parse(e.payloadJson()));
        return env;
    }

    static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("아웃박스 payload가 JSON이 아니다", ex);
        }
    }
}
