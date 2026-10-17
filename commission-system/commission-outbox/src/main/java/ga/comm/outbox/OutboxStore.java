package ga.comm.outbox;

import java.util.List;

/**
 * 아웃박스 저장 포트(설계서 §6.8, E1 D-1·D-2) — <b>append-only</b>. 행을 넣고, 순번을 한 번 붙이고, 읽는 것뿐이다.
 * 행을 고치거나 지우는 메서드는 없다(Oracle은 트리거가 한 번 더 거부한다 — V106).
 *
 * <p>트랜잭션: {@link #append}는 업무 트랜잭션 안에서 호출된다(업무와 같이 커밋·롤백 — C3). {@link #assignPending}은
 * <b>업무 트랜잭션 밖의 단독 트랜잭션</b>에서만 호출한다 — 같은 트랜잭션의 미커밋 행이 보이면 "커밋된 행에만 순번" 증명이 깨진다.
 */
public interface OutboxStore {

    /** 순번 없이(seq NULL) 적재한다. 같은 {@code eventId}가 이미 있으면 예외. */
    void append(OutboxEvent event);

    /**
     * 부여기(D-1): 순번 표를 잠그고({@code OUTBOX_SEQ … FOR UPDATE}) 보이는(=커밋된) 미부여 행을 {@code event_no} 순으로 최대
     * {@code max}건 골라 지금 최댓값 + 1부터 빈틈 없이 붙인다.
     *
     * @return 이번에 붙인 건수(0이면 미부여 행 없음)
     */
    int assignPending(int max);

    /** 순번이 붙은 행 중 {@code seq > afterSeq}를 seq 오름차순으로 최대 {@code limit}건. 미부여 행은 나오지 않는다. */
    List<StoredEvent> readAssignedAfter(long afterSeq, int limit);

    /** 부여된 최대 순번(없으면 0). */
    long headSeq();

    /** 같은 타입·집합의 행이 이미 있는가 — 경합에서 진 트랜잭션이 개설 사실을 두 번 내지 않게(LimitLedgerOpened). */
    boolean contains(String type, String aggregateKind, String aggregateId);
}
