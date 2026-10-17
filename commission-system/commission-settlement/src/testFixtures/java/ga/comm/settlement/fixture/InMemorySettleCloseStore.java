package ga.comm.settlement.fixture;

import ga.comm.domain.time.CloseYm;
import ga.comm.settlement.SettleCloseStore;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** 인메모리 SETTLE_CLOSE 저장소. */
public class InMemorySettleCloseStore implements SettleCloseStore {

    private final Map<CloseYm, CloseState> states = new HashMap<>();
    private final Map<CloseYm, String> forceReasons = new HashMap<>();
    private final Map<CloseYm, LocalDateTime> closedAts = new HashMap<>();

    @Override
    public synchronized CloseState stateOf(CloseYm closeYm) {
        return states.getOrDefault(closeYm, CloseState.OPEN);
    }

    @Override
    public synchronized void transition(CloseYm closeYm, CloseState to, String by, String forceReason,
                                        LocalDateTime closedAt) {
        states.put(closeYm, to);
        if (to == CloseState.CLOSED) { // 확정 시각은 CLOSED 전이에서만 쓴다(v1.2.5 — Oracle과 같은 시맨틱)
            if (closedAt == null) {
                closedAts.remove(closeYm);
            } else {
                closedAts.put(closeYm, closedAt);
            }
        }
        if (forceReason != null) { // null은 기존 사유 보존 (Oracle NVL 시맨틱과 일치)
            forceReasons.put(closeYm, forceReason);
        }
    }

    @Override
    public synchronized String forceReasonOf(CloseYm closeYm) {
        return forceReasons.get(closeYm);
    }

    @Override
    public synchronized Optional<LocalDateTime> closedAtOf(CloseYm closeYm) {
        return Optional.ofNullable(closedAts.get(closeYm));
    }
}
