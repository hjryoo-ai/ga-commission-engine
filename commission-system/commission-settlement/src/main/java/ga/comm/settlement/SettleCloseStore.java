package ga.comm.settlement;

import ga.comm.calc.store.CloseStatusProvider;
import ga.comm.domain.time.CloseYm;

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
    void transition(CloseYm closeYm, CloseState to, String by, String forceReason);

    default void transition(CloseYm closeYm, CloseState to, String by) {
        transition(closeYm, to, by, null);
    }

    /** 강제 마감 사유(SETTLE_CLOSE.force_reason). 없으면 null. */
    String forceReasonOf(CloseYm closeYm);

    static CloseStatusProvider asProvider(SettleCloseStore store) {
        return ym -> store.stateOf(ym) == CloseState.CLOSED;
    }
}
