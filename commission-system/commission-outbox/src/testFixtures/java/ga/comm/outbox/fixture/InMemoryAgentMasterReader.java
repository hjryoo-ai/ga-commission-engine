package ga.comm.outbox.fixture;

import ga.comm.outbox.feed.AgentMasterReader;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** 인메모리 설계사 마스터(AgentSnapshot 시험). 이력 시작일은 시험이 정한다(등급·소속 이력은 {@code InMemoryAgentDirectory}가 가진다). */
public class InMemoryAgentMasterReader implements AgentMasterReader {

    private final Map<String, AgentMaster> masters = new TreeMap<>();
    private final Map<String, List<LocalDate>> historyFroms = new TreeMap<>();

    public InMemoryAgentMasterReader with(AgentMaster master, LocalDate... historyFrom) {
        masters.put(master.agentId(), master);
        historyFroms.put(master.agentId(), new ArrayList<>(List.of(historyFrom)));
        return this;
    }

    @Override
    public List<String> agentIds() {
        return List.copyOf(masters.keySet());
    }

    @Override
    public Optional<AgentMaster> find(String agentId) {
        return Optional.ofNullable(masters.get(agentId));
    }

    @Override
    public Optional<LocalDate> latestHistoryFrom(String agentId, LocalDate asOf) {
        return historyFroms.getOrDefault(agentId, List.of()).stream().filter(d -> !d.isAfter(asOf)).max(LocalDate::compareTo);
    }
}
