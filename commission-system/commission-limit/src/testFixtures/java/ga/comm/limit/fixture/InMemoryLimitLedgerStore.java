package ga.comm.limit.fixture;

import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.limit.LimitLedger;
import ga.comm.limit.LimitLedgerStore;
import ga.comm.rule.model.LimitRule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** 인메모리 한도 원장 저장소. */
public class InMemoryLimitLedgerStore implements LimitLedgerStore {

    private final List<LimitLedger> ledgers = new ArrayList<>();
    private final AtomicLong idSeq = new AtomicLong(0);

    @Override
    public synchronized Optional<LimitLedger> find(PolicyNo policyNo, AgentId agentId) {
        return ledgers.stream()
                .filter(l -> l.policyNo().equals(policyNo) && l.agentId().equals(agentId))
                .findFirst();
    }

    @Override
    public synchronized LimitLedger getOrCreate(PolicyNo policyNo, AgentId agentId,
                                                LocalDate contractDate, Money monthlyPremium,
                                                LimitRule rule) {
        return find(policyNo, agentId).orElseGet(() -> {
            LocalDate fyStart = contractDate;
            LocalDate fyEnd = contractDate.plusMonths(rule.fyWindowMonths()).minusDays(1);
            LimitLedger ledger = new LimitLedger(idSeq.incrementAndGet(), policyNo, agentId,
                    contractDate, fyStart, fyEnd, monthlyPremium, rule.limitMultiple(), rule.ruleId());
            ledgers.add(ledger);
            return ledger;
        });
    }

    @Override
    public synchronized void save(LimitLedger ledger) {
        // 인메모리는 동일 인스턴스 공유 — no-op
    }

    @Override
    public synchronized List<LimitLedger> findAll() {
        return List.copyOf(ledgers);
    }
}
