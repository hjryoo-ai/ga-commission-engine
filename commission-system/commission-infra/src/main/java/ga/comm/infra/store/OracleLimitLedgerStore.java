package ga.comm.infra.store;

import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.infra.mapper.LimitLedgerMapper;
import ga.comm.limit.LimitLedger;
import ga.comm.limit.LimitLedgerStore;
import ga.comm.rule.model.LimitRule;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * LIMIT_LEDGER Oracle 어댑터 (설계서 §6.1.6 락 규약의 실체):
 *
 * <ul>
 *   <li>find/getOrCreate는 SELECT FOR UPDATE로 원장 행을 잠근다 — 게이트 판정([4])과
 *       저장 후 훅의 전기([5.5])가 커밋까지 하나의 직렬화 단위가 된다.</li>
 *   <li>신규 원장 동시 생성은 uq_limit 위반 → 잠금 재조회로 직렬화한다.</li>
 *   <li>posting_seq는 락 보유 상태에서 MAX+1로 채번한다 (부록 B-5, B-8). save()는
 *       DB에 없는 꼬리 전기(증분)만 추가한다 — 애그리게잇은 같은 트랜잭션에서 락과 함께
 *       적재됐으므로 선두 posting_seq개 전기는 DB와 항상 일치한다.</li>
 *   <li>재적재 시 accum_paid = Σ DTL 불변식을 검증한다 (rehydrate가 강제).</li>
 * </ul>
 */
public class OracleLimitLedgerStore implements LimitLedgerStore {

    private final LimitLedgerMapper mapper;

    public OracleLimitLedgerStore(LimitLedgerMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public Optional<LimitLedger> find(PolicyNo policyNo, AgentId agentId) {
        LimitLedgerMapper.Row row = mapper.lockByKey(policyNo.value(), agentId.value());
        return Optional.ofNullable(row).map(this::rehydrate);
    }

    @Override
    public LimitLedger getOrCreate(PolicyNo policyNo, AgentId agentId, LocalDate contractDate,
                                   Money monthlyPremium, LimitRule rule) {
        Optional<LimitLedger> existing = find(policyNo, agentId);
        if (existing.isPresent()) {
            return existing.get();
        }

        LocalDate fyStart = contractDate;
        LocalDate fyEnd = contractDate.plusMonths(rule.fyWindowMonths()).minusDays(1);
        LimitLedger fresh = new LimitLedger(0, policyNo, agentId, contractDate, fyStart, fyEnd,
                monthlyPremium, rule.limitMultiple(), rule.ruleId());

        LimitLedgerMapper.Row row = new LimitLedgerMapper.Row();
        row.policyNo = policyNo.value();
        row.agentId = agentId.value();
        row.contractDate = contractDate;
        row.fyStart = fyStart;
        row.fyEnd = fyEnd;
        row.monthlyPremium = monthlyPremium.toLong();
        row.limitMultiple = rule.limitMultiple();
        row.limitAmount = fresh.limitAmount().toLong();
        row.accumPaid = 0;
        row.ruleVersionId = rule.ruleId();
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException raced) {
            // 동시 개설 경합 — 승자의 행을 잠그고 재적재한다 (§6.1.6)
            return find(policyNo, agentId).orElseThrow(() -> new IllegalStateException(
                    "uq_limit 위반 후 원장 재조회 실패: " + policyNo + "/" + agentId));
        }
        return new LimitLedger(row.ledgerId, policyNo, agentId, contractDate, fyStart, fyEnd,
                monthlyPremium, rule.limitMultiple(), rule.ruleId());
    }

    @Override
    public void save(LimitLedger ledger) {
        LimitLedgerMapper.Row row = new LimitLedgerMapper.Row();
        row.ledgerId = ledger.ledgerId();
        row.monthlyPremium = ledger.monthlyPremium().toLong();
        row.limitAmount = ledger.limitAmount().toLong();
        row.accumPaid = ledger.accumPaid().toLong();
        if (mapper.update(row) == 0) {
            throw new IllegalStateException("존재하지 않는 원장입니다: ledgerId=" + ledger.ledgerId());
        }

        List<LimitLedger.Posting> postings = ledger.postings();
        long persisted = mapper.maxPostingSeq(ledger.ledgerId());
        if (persisted > postings.size()) {
            throw new IllegalStateException("원장 전기 내역이 애그리게잇보다 큽니다: ledgerId="
                    + ledger.ledgerId() + " DB=" + persisted + " aggregate=" + postings.size());
        }
        for (int i = (int) persisted; i < postings.size(); i++) {
            LimitLedger.Posting posting = postings.get(i);
            mapper.insertPosting(ledger.ledgerId(), i + 1L, posting.calcId(), posting.amount().toLong());
        }
    }

    @Override
    public List<LimitLedger> findAll() {
        return mapper.findAll().stream().map(this::rehydrate).toList();
    }

    private LimitLedger rehydrate(LimitLedgerMapper.Row row) {
        List<LimitLedger.Posting> postings = mapper.postings(row.ledgerId).stream()
                .map(p -> new LimitLedger.Posting(p.calcId, Money.won(p.amount)))
                .toList();
        return LimitLedger.rehydrate(row.ledgerId, new PolicyNo(row.policyNo), new AgentId(row.agentId),
                row.contractDate, row.fyStart, row.fyEnd, Money.won(row.monthlyPremium),
                row.limitMultiple, row.ruleVersionId, Money.won(row.limitAmount),
                Money.won(row.accumPaid), postings);
    }
}
