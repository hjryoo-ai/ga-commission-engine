package ga.comm.settlement.fixture;

import ga.comm.domain.time.CloseYm;
import ga.comm.settlement.AgentSettlementStore;
import ga.comm.settlement.SettlementRow;

import java.util.ArrayList;
import java.util.List;

/** 인메모리 마감 정산 내역 저장소. */
public class InMemoryAgentSettlementStore implements AgentSettlementStore {

    private final List<SettlementRow> rows = new ArrayList<>();

    @Override
    public synchronized void saveAll(List<SettlementRow> newRows) {
        rows.addAll(newRows);
    }

    @Override
    public synchronized List<SettlementRow> findByCloseYm(CloseYm closeYm) {
        return rows.stream().filter(r -> r.closeYm().equals(closeYm)).toList();
    }
}
