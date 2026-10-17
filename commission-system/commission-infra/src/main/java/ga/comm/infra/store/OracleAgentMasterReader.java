package ga.comm.infra.store;

import ga.comm.infra.mapper.AgentMasterMapper;
import ga.comm.outbox.feed.AgentMasterReader;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** {@link AgentMasterReader}의 Oracle 어댑터(Phase E1). */
public class OracleAgentMasterReader implements AgentMasterReader {

    private final AgentMasterMapper mapper;

    public OracleAgentMasterReader(AgentMasterMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public List<String> agentIds() {
        return mapper.agentIds();
    }

    @Override
    public Optional<AgentMaster> find(String agentId) {
        return Optional.ofNullable(mapper.find(agentId))
                .map(r -> new AgentMaster(r.agentId, r.agentName, r.appointedOn, r.dismissedOn));
    }

    @Override
    public Optional<LocalDate> latestHistoryFrom(String agentId, LocalDate asOf) {
        return Optional.ofNullable(mapper.latestHistoryFrom(agentId, asOf));
    }
}
