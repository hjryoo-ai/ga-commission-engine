package ga.comm.outbox.contract;

import ga.comm.outbox.OutboxEvent;
import ga.comm.outbox.OutboxStore;
import ga.comm.outbox.StoredEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OutboxStore} 계약 스위트(설계서 §8.7, E1 C2) — 인메모리 레퍼런스와 Oracle 어댑터(H2 티어 포함)가 상속한다.
 * 공유 DB(Oracle IT)에서는 앞 시험의 행이 남으므로(append-only — 지우지 않는다) 모든 단언을 시작 시점의 {@code headSeq}에 상대적으로 쓴다.
 */
public abstract class OutboxStoreContract {

    protected abstract OutboxStore store();

    /** 호출 하나를 트랜잭션 하나로(인메모리는 그대로 실행). */
    protected <T> T tx(Supplier<T> work) {
        return work.get();
    }

    private long base;

    @BeforeEach
    void drainAndMark() {
        while (tx(() -> store().assignPending(1000)) > 0) {
            // 앞 시험이 남긴 미부여 행을 모두 붙여 기준점을 만든다
        }
        base = tx(() -> store().headSeq());
    }

    protected static OutboxEvent event(String type, String kind, String id) {
        return new OutboxEvent(UUID.randomUUID(), type, 1, "2026-09-05T18:00:00+09:00", kind, id,
                "{\"agentId\":\"A-1001\",\"amount\":2800000,\"rate\":\"9.333333\"}");
    }

    private void append(OutboxEvent e) {
        tx(() -> {
            store().append(e);
            return null;
        });
    }

    @Test
    void 적재한_행은_순번이_붙기_전에는_읽히지_않는다() {
        append(event("CommissionCalculated", "COMM_CALC", "1"));
        assertThat(tx(() -> store().readAssignedAfter(base, 100))).isEmpty();
        assertThat(tx(() -> store().headSeq())).isEqualTo(base);
    }

    @Test
    void 부여는_적재_순서대로_빈틈_없이_이어진다() {
        List<OutboxEvent> events = List.of(event("AgentSnapshot", "AGENT", "A-1"), event("AgentSnapshot", "AGENT", "A-2"),
                event("AgentSnapshot", "AGENT", "A-3"));
        events.forEach(this::append);
        assertThat(tx(() -> store().assignPending(1000))).isEqualTo(3);
        List<StoredEvent> read = tx(() -> store().readAssignedAfter(base, 100));
        assertThat(read).extracting(StoredEvent::seq).containsExactly(base + 1, base + 2, base + 3);
        assertThat(read).extracting(r -> r.event().eventId()).containsExactlyElementsOf(events.stream().map(OutboxEvent::eventId).toList());
        assertThat(tx(() -> store().headSeq())).isEqualTo(base + 3);
        assertThat(tx(() -> store().assignPending(1000))).as("이미 붙은 행에는 다시 붙이지 않는다").isZero();
    }

    @Test
    void 부여_상한과_다음_부여의_연속() {
        for (int i = 0; i < 5; i++) {
            append(event("AgentSnapshot", "AGENT", "A-" + i));
        }
        assertThat(tx(() -> store().assignPending(2))).isEqualTo(2);
        assertThat(tx(() -> store().headSeq())).isEqualTo(base + 2);
        assertThat(tx(() -> store().assignPending(10))).isEqualTo(3);
        assertThat(tx(() -> store().readAssignedAfter(base, 100))).extracting(StoredEvent::seq)
                .containsExactly(base + 1, base + 2, base + 3, base + 4, base + 5);
    }

    @Test
    void 읽기는_afterSeq_초과를_오름차순으로_limit까지() {
        for (int i = 0; i < 4; i++) {
            append(event("AgentSnapshot", "AGENT", "A-" + i));
        }
        tx(() -> store().assignPending(1000));
        assertThat(tx(() -> store().readAssignedAfter(base + 1, 2))).extracting(StoredEvent::seq).containsExactly(base + 2, base + 3);
        assertThat(tx(() -> store().readAssignedAfter(base + 4, 10))).isEmpty();
    }

    @Test
    void 저장한_내용은_바이트_그대로_돌아온다() {
        OutboxEvent e = new OutboxEvent(UUID.randomUUID(), "StatementConfirmed", 1, "2026-09-05T18:00:00+09:00", "STATEMENT",
                "A-1001:202608", "{\"agentId\":\"A-1001\",\"closeYm\":\"202608\",\"gross\":-120,\"memo\":\"한글 \\\"인용\\\"\"}");
        append(e);
        tx(() -> store().assignPending(1000));
        StoredEvent read = tx(() -> store().readAssignedAfter(base, 1)).get(0);
        assertThat(read.event()).isEqualTo(e);
        assertThat(read.eventNo()).isPositive();
    }

    @Test
    void 같은_eventId는_두_번_적재되지_않는다() {
        OutboxEvent e = event("AgentSnapshot", "AGENT", "A-dup");
        append(e);
        assertThatThrownBy(() -> append(e)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void 같은_타입_집합의_존재_여부() {
        String id = "L-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(tx(() -> store().contains("LimitLedgerOpened", "LIMIT_LEDGER", id))).isFalse();
        append(event("LimitLedgerOpened", "LIMIT_LEDGER", id));
        assertThat(tx(() -> store().contains("LimitLedgerOpened", "LIMIT_LEDGER", id))).isTrue();
        assertThat(tx(() -> store().contains("LimitLedgerPosted", "LIMIT_LEDGER", id))).isFalse();
    }

    /** append-only(D-2): 포트에 고치거나 지우는 메서드가 없다 — 메서드 집합을 고정한다. */
    @Test
    void 포트는_적재_부여_읽기뿐이다() {
        assertThat(Arrays.stream(OutboxStore.class.getDeclaredMethods()).map(Method::getName).sorted())
                .containsExactly("append", "assignPending", "contains", "headSeq", "readAssignedAfter");
    }
}
