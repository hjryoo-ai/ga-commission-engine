package ga.comm.settlement.simulation;

import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.limit.LimitLedger;
import ga.comm.limit.LimitLedgerStore;
import ga.comm.rule.model.LimitRule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 한도 원장 오버레이(E2 ② — 계획 P3). 운영 {@code find}·{@code getOrCreate}는 Oracle에서 {@code SELECT … FOR UPDATE}로 잠그고 없으면
 * {@code INSERT}하며, 운영 빈은 발행 데코레이터라 개설이 {@code LimitLedgerOpened}를 적재한다. 오버레이는 원장을 <b>잠그지 않는 조회</b>
 * ({@code peek})로 한 번 읽어 <b>사본</b>을 만들고(인메모리 원 저장소는 같은 인스턴스를 줄 수 있다 — 고치면 원본이 바뀐다), 개설·전기·재산정은
 * 사본에만 한다. {@code save}는 아무것도 하지 않는다(사본이 곧 상태).
 */
public final class OverlayLimitLedgerStore implements LimitLedgerStore {

    private record Key(PolicyNo policyNo, AgentId agentId) {
    }

    private final SimulationReads reads;
    private final Map<Key, Optional<LimitLedger>> copies = new LinkedHashMap<>();
    private long nextId = 0;

    public OverlayLimitLedgerStore(SimulationReads reads) {
        this.reads = Objects.requireNonNull(reads);
    }

    @Override
    public Optional<LimitLedger> find(PolicyNo policyNo, AgentId agentId) {
        return copies.computeIfAbsent(new Key(policyNo, agentId),
                k -> reads.ledgerPeek().apply(policyNo, agentId).map(OverlayLimitLedgerStore::copyOf));
    }

    @Override
    public Optional<LimitLedger> peek(PolicyNo policyNo, AgentId agentId) {
        return find(policyNo, agentId);
    }

    @Override
    public LimitLedger getOrCreate(PolicyNo policyNo, AgentId agentId, LocalDate contractDate, Money monthlyPremium, LimitRule rule) {
        Optional<LimitLedger> existing = find(policyNo, agentId);
        if (existing.isPresent()) {
            return existing.get();
        }
        // 개설 값은 운영 저장소(OracleLimitLedgerStore.getOrCreate)와 같다 — fyEnd = 체결일 + 창 − 1일
        LimitLedger fresh = new LimitLedger(--nextId, policyNo, agentId, contractDate, contractDate,
                contractDate.plusMonths(rule.fyWindowMonths()).minusDays(1), monthlyPremium, rule.limitMultiple(), rule.ruleId());
        copies.put(new Key(policyNo, agentId), Optional.of(fresh));
        return fresh;
    }

    @Override
    public void save(LimitLedger ledger) {
        // 사본이 곧 상태 — 원 저장소에 쓰지 않는다
    }

    @Override
    public List<LimitLedger> findAll() {
        List<LimitLedger> out = new ArrayList<>();
        copies.values().forEach(o -> o.ifPresent(out::add));
        return out;
    }

    private static LimitLedger copyOf(LimitLedger l) {
        return LimitLedger.rehydrate(l.ledgerId(), l.policyNo(), l.agentId(), l.contractDate(), l.fyStart(), l.fyEnd(),
                l.monthlyPremium(), l.limitMultiple(), l.ruleVersionId(), l.limitAmount(), l.accumPaid(), l.postings());
    }
}
