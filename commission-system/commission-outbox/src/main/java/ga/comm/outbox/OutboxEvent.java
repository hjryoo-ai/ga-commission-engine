package ga.comm.outbox;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 아웃박스 행 하나의 내용(설계서 §6.8) — 포털 Envelope의 {@code seq}를 뺀 전부. {@code seq}는 순번 부여기가 커밋된 행에만 붙인다(D-1).
 *
 * @param eventId       발행 시 생성한 UUID(소문자) — 포털의 멱등 키
 * @param type          포털 계약의 이벤트 타입(13종)
 * @param version       payload 스키마 판(현재 1)
 * @param occurredAt    사실의 시각(업무 시각 — 주입된 시계), ISO-8601 오프셋 포함 문자열. 저장한 바이트 그대로 피드에 나간다
 * @param aggregateKind 포털 계약의 집합 종류(9종 — type과 짝)
 * @param aggregateId   집합 식별자(1~64자). STATEMENT·PAYOUT은 {@code {agentId}:{closeYm}}
 * @param payloadJson   payload JSON 객체(금액은 정수 원, 소수는 문자열)
 */
public record OutboxEvent(UUID eventId, String type, int version, String occurredAt,
                          String aggregateKind, String aggregateId, String payloadJson) {

    private static final Pattern TYPE = Pattern.compile("^[A-Z][A-Za-z0-9]{0,39}$");
    private static final Pattern KIND = Pattern.compile("^[A-Z][A-Z0-9_]{0,29}$");
    private static final Pattern INSTANT = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$");

    public OutboxEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(aggregateKind, "aggregateKind");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(payloadJson, "payloadJson");
        if (!TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("이벤트 타입 형식 위반: " + type);
        }
        if (version < 1) {
            throw new IllegalArgumentException("이벤트 판은 1 이상: " + version);
        }
        if (!INSTANT.matcher(occurredAt).matches()) {
            throw new IllegalArgumentException("occurredAt은 오프셋 포함 ISO-8601: " + occurredAt);
        }
        if (!KIND.matcher(aggregateKind).matches()) {
            throw new IllegalArgumentException("집합 종류 형식 위반: " + aggregateKind);
        }
        if (aggregateId.isEmpty() || aggregateId.length() > 64) {
            throw new IllegalArgumentException("집합 식별자는 1~64자: " + aggregateId);
        }
        if (!payloadJson.startsWith("{")) {
            throw new IllegalArgumentException("payload는 JSON 객체여야 한다");
        }
    }
}
