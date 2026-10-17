package ga.comm.outbox;

import java.util.Objects;

/**
 * 순번 부여기(D-1, 설계서 §6.8) — {@link OutboxStore#assignPending}을 한 번에 최대 {@code batchSize}건씩 부른다.
 * 피드 호출(lazy, P8)과 배치 잡이 같은 메서드를 쓴다. 호출자가 <b>단독 쓰기 트랜잭션</b>으로 감싼다(업무 트랜잭션 밖).
 */
public final class OutboxSequencer {

    private final OutboxStore store;
    private final int batchSize;

    public OutboxSequencer(OutboxStore store, int batchSize) {
        this.store = Objects.requireNonNull(store);
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("부여 건수 상한은 1~10000: " + batchSize);
        }
        this.batchSize = batchSize;
    }

    /** 한 트랜잭션 분량을 붙인다. @return 붙인 건수 */
    public int assignOnce() {
        return store.assignPending(batchSize);
    }

    public int batchSize() {
        return batchSize;
    }
}
