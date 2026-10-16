package ga.comm.outbox;

import java.util.Objects;

/**
 * 저장된 아웃박스 행 — {@code eventNo}는 적재 순서(IDENTITY), {@code seq}는 부여기가 커밋된 행에만 붙인 피드 순번(없으면 null).
 */
public record StoredEvent(long eventNo, Long seq, OutboxEvent event) {

    public StoredEvent {
        Objects.requireNonNull(event, "event");
        if (seq != null && seq < 1) {
            throw new IllegalArgumentException("seq는 1 이상: " + seq);
        }
    }
}
