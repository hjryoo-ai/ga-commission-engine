package ga.comm.settlement.fixture;

import ga.comm.domain.time.CloseYm;
import ga.comm.settlement.SettleCloseStore;

import java.util.HashMap;
import java.util.Map;

/** 인메모리 SETTLE_CLOSE 저장소. */
public class InMemorySettleCloseStore implements SettleCloseStore {

    private final Map<CloseYm, CloseState> states = new HashMap<>();
    private final Map<CloseYm, String> forceReasons = new HashMap<>();

    @Override
    public synchronized CloseState stateOf(CloseYm closeYm) {
        return states.getOrDefault(closeYm, CloseState.OPEN);
    }

    @Override
    public synchronized void transition(CloseYm closeYm, CloseState to, String by, String forceReason) {
        states.put(closeYm, to);
        if (forceReason != null) { // null은 기존 사유 보존 (Oracle NVL 시맨틱과 일치)
            forceReasons.put(closeYm, forceReason);
        }
    }

    @Override
    public synchronized String forceReasonOf(CloseYm closeYm) {
        return forceReasons.get(closeYm);
    }
}
