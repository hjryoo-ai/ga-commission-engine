package ga.comm.clawback.fixture;

import ga.comm.clawback.ClawbackReceivable;
import ga.comm.clawback.ClawbackReceivableStore;
import ga.comm.clawback.ReceivableStatus;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.money.Money;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 인메모리 환수 채권 저장소. */
public class InMemoryClawbackReceivableStore implements ClawbackReceivableStore {

    public record OffsetHist(long receivableId, long calcId, Money amount) {
    }

    private final List<ClawbackReceivable> receivables = new ArrayList<>();
    private final List<OffsetHist> offsetHist = new ArrayList<>();
    private final AtomicLong idSeq = new AtomicLong(0);

    @Override
    public synchronized ClawbackReceivable create(AgentId agentId, Long originCalcId, Money amount) {
        ClawbackReceivable receivable =
                new ClawbackReceivable(idSeq.incrementAndGet(), agentId, originCalcId, amount);
        receivables.add(receivable);
        return receivable;
    }

    @Override
    public synchronized List<ClawbackReceivable> findOffsettable(AgentId agentId) {
        return receivables.stream()
                .filter(r -> r.agentId().equals(agentId))
                .filter(r -> r.status() == ReceivableStatus.OPEN || r.status() == ReceivableStatus.OFFSET)
                .toList();
    }

    @Override
    public synchronized void save(ClawbackReceivable receivable) {
        // 인메모리는 동일 인스턴스 공유 — no-op
    }

    @Override
    public synchronized void recordOffset(long receivableId, long calcId, Money amount) {
        offsetHist.add(new OffsetHist(receivableId, calcId, amount));
    }

    public synchronized List<OffsetHist> offsetHistory() {
        return List.copyOf(offsetHist);
    }

    public synchronized List<ClawbackReceivable> all() {
        return List.copyOf(receivables);
    }
}
