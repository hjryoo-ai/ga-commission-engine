package ga.comm.infra.it;

import ga.comm.infra.OraclePersistence;
import ga.comm.outbox.OutboxEvent;
import ga.comm.outbox.OutboxStore;
import ga.comm.outbox.StoredEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E1 C2 — 순번 부여기의 실제 경합(Oracle, 두 커넥션):
 * <ol>
 *   <li>미커밋 업무 트랜잭션의 행은 부여되지 않고, 그 트랜잭션이 커밋된 뒤 다음 부여에서 이어지는 번호를 받는다(갭 0).</li>
 *   <li>두 부여기가 겹치면 두 번째는 {@code OUTBOX_SEQ FOR UPDATE}에서 첫 번째의 커밋을 기다렸다가 이어서 붙인다 — 중복·갭 없음.
 *       잠금이 없으면 두 번째가 첫 번째가 붙인 행을 다시 붙이려다 실패한다(주입 I2).</li>
 * </ol>
 */
class OutboxSequencerOracleIT {

    private final OraclePersistence persistence = OracleTestSupport.persistence();
    private final OutboxStore store = persistence.outboxStore();
    private long base;

    @BeforeEach
    void drain() {
        while (persistence.inTx(() -> store.assignPending(1000)) > 0) {
            // 앞 시험의 미부여 행
        }
        base = persistence.inTx(store::headSeq);
    }

    private static OutboxEvent event(String id) {
        return new OutboxEvent(UUID.randomUUID(), "AgentSnapshot", 1, "2026-09-05T18:00:00+09:00", "AGENT", id,
                "{\"agentId\":\"" + id + "\"}");
    }

    @Test
    void 미커밋_행은_부여되지_않고_커밋_뒤_다음_번호를_받는다() throws Exception {
        OutboxEvent late = event("A-LATE");
        OutboxEvent early = event("A-EARLY");
        CountDownLatch appended = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 업무 트랜잭션 1: 먼저 적재(event_no가 더 작다)하고 커밋하지 않은 채 붙잡는다
        CompletableFuture<Void> slow = CompletableFuture.runAsync(() -> persistence.inTx(() -> {
            store.append(late);
            appended.countDown();
            await(release);
        }));
        assertThat(appended.await(30, TimeUnit.SECONDS)).isTrue();
        // 업무 트랜잭션 2: 나중에 적재하고 바로 커밋
        persistence.inTx(() -> store.append(early));

        assertThat(persistence.inTx(() -> store.assignPending(1000))).as("커밋된 행만").isEqualTo(1);
        List<StoredEvent> first = persistence.inTx(() -> store.readAssignedAfter(base, 10));
        assertThat(first).extracting(s -> s.event().eventId()).containsExactly(early.eventId());
        assertThat(first.get(0).seq()).isEqualTo(base + 1);

        release.countDown();
        slow.get(30, TimeUnit.SECONDS);
        assertThat(persistence.inTx(() -> store.assignPending(1000))).isEqualTo(1);
        List<StoredEvent> all = persistence.inTx(() -> store.readAssignedAfter(base, 10));
        assertThat(all).extracting(StoredEvent::seq).containsExactly(base + 1, base + 2);
        assertThat(all.get(1).event().eventId()).isEqualTo(late.eventId());
        assertThat(all.get(1).eventNo()).as("event_no는 더 작지만 늦게 커밋돼 더 큰 seq — 이미 나간 번호 사이에 끼지 않는다")
                .isLessThan(all.get(0).eventNo());
    }

    @Test
    void 겹친_부여는_잠금에서_직렬화된다() throws Exception {
        for (int i = 0; i < 6; i++) {
            persistence.inTx(() -> store.append(event("A-RACE")));
        }
        CountDownLatch firstAssigned = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        AtomicLong firstCommittedAt = new AtomicLong();
        // 부여기 1: 3건을 붙이고 커밋하지 않은 채 붙잡는다(잠금 보유)
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> persistence.inTx(() -> {
            int n = store.assignPending(3);
            firstAssigned.countDown();
            await(commitFirst);
            firstCommittedAt.set(System.nanoTime());
            return n;
        }));
        assertThat(firstAssigned.await(30, TimeUnit.SECONDS)).isTrue();
        // 부여기 2: 나머지를 붙이려 한다 — 잠금이 있으면 부여기 1의 커밋까지 기다린다
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> persistence.inTx(() -> store.assignPending(1000)));
        Thread.sleep(1500);
        assertThat(second).as("부여기 2는 잠금에서 기다리는 중").isNotDone();
        commitFirst.countDown();
        assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(3);
        assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(3);

        List<StoredEvent> all = persistence.inTx(() -> store.readAssignedAfter(base, 100));
        assertThat(all).extracting(StoredEvent::seq).containsExactly(base + 1, base + 2, base + 3, base + 4, base + 5, base + 6);
        assertThat(persistence.inTx(store::headSeq)).isEqualTo(base + 6);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
