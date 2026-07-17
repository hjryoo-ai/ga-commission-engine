package ga.comm.infra.store;

import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.RecipientType;
import ga.comm.infra.mapper.AgentSettlementMapper;
import ga.comm.settlement.AgentSettlementStore;
import ga.comm.settlement.SettlementRow;

import java.util.List;
import java.util.Objects;

/** AGENT_SETTLEMENT Oracle 어댑터 — 근거 calcId 목록은 calc_seq로 순서를 보존한다. */
public class OracleAgentSettlementStore implements AgentSettlementStore {

    private final AgentSettlementMapper mapper;

    public OracleAgentSettlementStore(AgentSettlementMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public void saveAll(List<SettlementRow> rows) {
        for (SettlementRow settlement : rows) {
            AgentSettlementMapper.Row row = new AgentSettlementMapper.Row();
            row.closeYm = settlement.closeYm().value();
            row.runSeq = settlement.runSeq();
            row.recipientType = settlement.recipientType().name();
            row.recipientId = settlement.recipientId();
            row.grossNet = settlement.grossNet().toLong();
            row.receivableOffset = settlement.receivableOffset().toLong();
            row.carriedReceivable = settlement.carriedReceivable().toLong();
            row.payable = settlement.payable().toLong();
            mapper.insert(row);

            List<Long> calcIds = settlement.calcIds();
            for (int i = 0; i < calcIds.size(); i++) {
                mapper.insertCalc(row.settlementId, i + 1L, calcIds.get(i));
            }
        }
    }

    @Override
    public List<SettlementRow> findByCloseYm(CloseYm closeYm) {
        return mapper.findByCloseYm(closeYm.value()).stream()
                .map(row -> new SettlementRow(CloseYm.of(row.closeYm), row.runSeq,
                        RecipientType.valueOf(row.recipientType), row.recipientId,
                        Money.won(row.grossNet), Money.won(row.receivableOffset),
                        Money.won(row.carriedReceivable), Money.won(row.payable),
                        mapper.calcIds(row.settlementId)))
                .toList();
    }
}
