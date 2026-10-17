package ga.comm.settlement;

import ga.comm.calc.store.CloseStatusProvider;
import ga.comm.domain.time.CloseYm;

import java.time.LocalDateTime;
import java.util.Optional;

/** 마감 상태 저장 포트 (SETTLE_CLOSE). */
public interface SettleCloseStore {

    enum CloseState {
        OPEN,
        CLOSING,
        CLOSED
    }

    /** 레코드가 없으면 OPEN. */
    CloseState stateOf(CloseYm closeYm);

    /**
     * 마감 상태 전이. 강제 마감 사유가 있으면 업무 테이블에 함께 영속한다(v1.1.5 §7).
     * {@code forceReason}이 null이면 기존에 기록된 사유를 보존한다(NVL 시맨틱) — 일반 전이가
     * 앞서 기록된 강제 마감 사유를 지우지 않도록.
     */
    default void transition(CloseYm closeYm, CloseState to, String by, String forceReason) {
        transition(closeYm, to, by, forceReason, null);
    }

    default void transition(CloseYm closeYm, CloseState to, String by) {
        transition(closeYm, to, by, null, null);
    }

    /**
     * 마감 상태 전이 + 확정 시각(Phase E1 Q5). {@code closed_at}은 <b>CLOSED로 갈 때만</b> {@code closedAt}(호출자의 주입된 시계, 그 시계 시간대의
     * 현지 시각)으로 쓰고, CLOSING·OPEN 전이는 건드리지 않는다 — 전에는 모든 전이가 DB 시각으로 덮어썼다(v1.2.5 동작 변경, §7).
     */
    void transition(CloseYm closeYm, CloseState to, String by, String forceReason, LocalDateTime closedAt);

    /** CLOSED 전이에 기록된 확정 시각. 없으면 empty. */
    Optional<LocalDateTime> closedAtOf(CloseYm closeYm);

    /** 강제 마감 사유(SETTLE_CLOSE.force_reason). 없으면 null. */
    String forceReasonOf(CloseYm closeYm);

    static CloseStatusProvider asProvider(SettleCloseStore store) {
        return ym -> store.stateOf(ym) == CloseState.CLOSED;
    }
}
