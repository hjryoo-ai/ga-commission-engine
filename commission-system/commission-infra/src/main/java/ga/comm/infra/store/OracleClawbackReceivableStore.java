package ga.comm.infra.store;

import ga.comm.clawback.ClawbackReceivable;
import ga.comm.clawback.ClawbackReceivableStore;
import ga.comm.clawback.ReceivableStatus;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.money.Money;
import ga.comm.infra.mapper.ClawbackReceivableMapper;

import java.util.List;
import java.util.Objects;

/** CLAWBACK_RECEIVABLE Oracle 어댑터. */
public class OracleClawbackReceivableStore implements ClawbackReceivableStore {

    private final ClawbackReceivableMapper mapper;

    public OracleClawbackReceivableStore(ClawbackReceivableMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public ClawbackReceivable create(AgentId agentId, Long originCalcId, Money amount) {
        ClawbackReceivableMapper.Row row = new ClawbackReceivableMapper.Row();
        row.agentId = agentId.value();
        row.originCalcId = originCalcId;
        row.amount = amount.toLong();
        row.remaining = amount.toLong();
        row.status = ReceivableStatus.OPEN.name();
        mapper.insert(row);
        return new ClawbackReceivable(row.receivableId, agentId, originCalcId, amount);
    }

    @Override
    public List<ClawbackReceivable> findOffsettable(AgentId agentId) {
        return mapper.findOffsettable(agentId.value()).stream()
                .map(OracleClawbackReceivableStore::toDomain).toList();
    }

    @Override
    public void save(ClawbackReceivable receivable) {
        int updated = mapper.updateState(receivable.receivableId(),
                receivable.remaining().toLong(), receivable.status().name());
        if (updated == 0) {
            throw new IllegalStateException("존재하지 않는 채권입니다: " + receivable.receivableId());
        }
    }

    @Override
    public void recordOffset(long receivableId, long calcId, Money amount) {
        mapper.insertOffsetHist(receivableId, calcId, amount.toLong());
    }

    private static ClawbackReceivable toDomain(ClawbackReceivableMapper.Row row) {
        return ClawbackReceivable.rehydrate(row.receivableId, new AgentId(row.agentId),
                row.originCalcId, Money.won(row.amount), Money.won(row.remaining),
                ReceivableStatus.valueOf(row.status));
    }
}
