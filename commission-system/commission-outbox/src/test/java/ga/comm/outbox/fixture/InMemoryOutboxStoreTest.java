package ga.comm.outbox.fixture;

import ga.comm.outbox.OutboxStore;
import ga.comm.outbox.contract.OutboxStoreContract;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 인메모리 레퍼런스가 아웃박스 계약 스위트를 통과한다 + 동시 부여가 직렬화된다(빈틈·중복 없음). */
class InMemoryOutboxStoreTest extends OutboxStoreContract {

    private final InMemoryOutboxStore store = new InMemoryOutboxStore();

    @Override
    protected OutboxStore store() {
        return store;
    }

    @Test
    void 동시_부여는_빈틈도_중복도_없다() throws Exception {
        for (int i = 0; i < 2000; i++) {
            store.append(event("AgentSnapshot", "AGENT", "A-" + i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            results.add(pool.submit(() -> {
                go.await();
                int sum = 0;
                int n;
                while ((n = store.assignPending(7)) > 0) {
                    sum += n;
                }
                return sum;
            }));
        }
        go.countDown();
        int total = 0;
        for (Future<Integer> f : results) {
            total += f.get();
        }
        pool.shutdown();
        assertThat(total).isEqualTo(2000);
        assertThat(store.readAssignedAfter(0, 5000)).extracting(r -> r.seq())
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 2000).boxed().toList());
    }
}
