package ga.comm.outbox.publish;

import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.limit.LimitLedger;
import ga.comm.limit.LimitLedgerStore;
import ga.comm.rule.model.LimitRule;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * 한도 원장의 세 사실(D-9, E1 Q2) — 게이트·포스터·환수 차감·부활 재전기·reversal 훅·재산정이 전부 이 포트의
 * {@code find}/{@code getOrCreate} → {@code save}를 거친다.
 *
 * <ul>
 *   <li>{@code LimitLedgerOpened} — {@code getOrCreate} 직전 {@code find}가 비었고, 이 원장의 개설 이벤트가 아직 없을 때. Oracle은 동시 개설
 *       경합에서 진 트랜잭션도 승자의 원장을 돌려받으므로({@code uq_limit} 재조회) 아웃박스 대조로 두 번 내지 않는다(승자 커밋 뒤에야 진행).</li>
 *   <li>{@code LimitLedgerRebased} — {@code save} 시점 {@code limit_amount}가 적재 때와 다를 때. {@code postingSeq}는 적재 때의 마지막 전기
 *       순번(0이면 생략 — 계약 v1.1.1 L1). 같은 {@code save}에 새 전기가 함께 있으면 재산정을 먼저 낸다.</li>
 *   <li>{@code LimitLedgerPosted} — {@code save}로 새로 생긴 꼬리 전기마다. {@code postingSeq} = 순번(Oracle {@code MAX+1} 채번과 같은 값 —
 *       애그리게잇의 선두 전기는 DB와 항상 일치), {@code accumAfter} = 그 전기 직후 누적.</li>
 * </ul>
 *
 * <p>"적재 때" 상태는 원장 <b>인스턴스</b>마다 기억한다. Oracle은 적재마다 새 인스턴스(원장 행 {@code FOR UPDATE} 잠금 안 — 같은 원장의 다른
 * 트랜잭션은 커밋까지 기다린다), 인메모리는 같은 인스턴스를 공유하므로 같은 인스턴스를 다시 적재하면 기억을 유지한다. {@code save} 뒤에는 저장된 상태가 그 인스턴스의
 * 다음 기준이다.
 * 이 데코레이터를 거쳐 적재하지 않은 인스턴스의 {@code save}는 실패한다(전기를 놓치지 않게).
 */
public final class OutboxLimitLedgerStore implements LimitLedgerStore {

    public static final String OPENED = "LimitLedgerOpened";
    public static final String POSTED = "LimitLedgerPosted";
    public static final String REBASED = "LimitLedgerRebased";
    public static final String KIND = "LIMIT_LEDGER";

    private record Loaded(int postings, Money limitAmount) {
    }

    private final LimitLedgerStore delegate;
    private final OutboxPublisher publisher;
    /** 원장 인스턴스(동일성 — {@link LimitLedger}는 equals를 재정의하지 않는다) → 적재 때 상태. 약한 키라 버려진 인스턴스는 사라진다. */
    private final Map<LimitLedger, Loaded> loaded = Collections.synchronizedMap(new WeakHashMap<>());

    public OutboxLimitLedgerStore(LimitLedgerStore delegate, OutboxPublisher publisher) {
        this.delegate = Objects.requireNonNull(delegate);
        this.publisher = Objects.requireNonNull(publisher);
    }

    @Override
    public Optional<LimitLedger> find(PolicyNo policyNo, AgentId agentId) {
        Optional<LimitLedger> found = delegate.find(policyNo, agentId);
        found.ifPresent(this::remember);
        return found;
    }

    @Override
    public LimitLedger getOrCreate(PolicyNo policyNo, AgentId agentId, LocalDate contractDate, Money monthlyPremium,
                                   LimitRule rule) {
        boolean absent = delegate.find(policyNo, agentId).isEmpty();
        LimitLedger ledger = delegate.getOrCreate(policyNo, agentId, contractDate, monthlyPremium, rule);
        String id = Long.toString(ledger.ledgerId());
        if (absent && !publisher.alreadyPublished(OPENED, KIND, id)) {
            publisher.publish(OPENED, KIND, id, PortalPayloads.limitLedgerOpened(ledger));
        }
        remember(ledger);
        return ledger;
    }

    @Override
    public void save(LimitLedger ledger) {
        Loaded before = loaded.get(ledger);
        if (before == null) {
            throw new IllegalStateException("아웃박스 원장 데코레이터를 거쳐 적재하지 않은 원장을 저장하려 했다: ledgerId="
                    + ledger.ledgerId());
        }
        delegate.save(ledger);
        String id = Long.toString(ledger.ledgerId());
        if (!ledger.limitAmount().equals(before.limitAmount())) {
            publisher.publish(REBASED, KIND, id, PortalPayloads.limitLedgerRebased(ledger, before.limitAmount(), before.postings()));
        }
        List<LimitLedger.Posting> postings = ledger.postings();
        Money accum = Money.ZERO;
        for (int i = 0; i < postings.size(); i++) {
            accum = accum.plus(postings.get(i).amount());
            if (i >= before.postings()) {
                publisher.publish(POSTED, KIND, id, PortalPayloads.limitLedgerPosted(ledger, i + 1, postings.get(i), accum));
            }
        }
        loaded.put(ledger, new Loaded(postings.size(), ledger.limitAmount()));   // 저장된 상태가 이 인스턴스의 다음 기준
    }

    @Override
    public List<LimitLedger> findAll() {
        return delegate.findAll();
    }

    private void remember(LimitLedger ledger) {
        loaded.putIfAbsent(ledger, new Loaded(ledger.postings().size(), ledger.limitAmount()));
    }
}
