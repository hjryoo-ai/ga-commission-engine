package ga.comm.infra.store;

import ga.comm.domain.time.CloseYm;
import ga.comm.infra.mapper.SettleCloseMapper;
import ga.comm.settlement.SettleCloseStore;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/** SETTLE_CLOSE Oracle 어댑터. */
public class OracleSettleCloseStore implements SettleCloseStore {

    private final SettleCloseMapper mapper;

    public OracleSettleCloseStore(SettleCloseMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public CloseState stateOf(CloseYm closeYm) {
        String state = mapper.stateOf(closeYm.value());
        return state == null ? CloseState.OPEN : CloseState.valueOf(state);
    }

    @Override
    public void transition(CloseYm closeYm, CloseState to, String by, String forceReason, LocalDateTime closedAt) {
        mapper.upsert(closeYm.value(), to.name(), by, forceReason, closedAt);
    }

    @Override
    public Optional<LocalDateTime> closedAtOf(CloseYm closeYm) {
        return Optional.ofNullable(mapper.closedAtOf(closeYm.value()));
    }

    @Override
    public String forceReasonOf(CloseYm closeYm) {
        return mapper.forceReasonOf(closeYm.value());
    }
}
