package ga.comm.settlement;

import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 마감월 불변성 가드 (설계서 §3.2, §6.4) — CLOSED 월로의 신규 귀속을 차단하는 데코레이터.
 * 마감 후 정정은 반드시 OPEN 월 귀속(reversal/ADJUSTMENT)으로만 가능하다.
 *
 * <p>상태 전이는 허용된다: CONFIRMED→PAID는 마감 후 지급 흐름이고, →REVERSED는 금액을
 * 바꾸지 않는 마킹이며 상쇄는 OPEN 월의 reversal 레코드가 담당한다.
 */
public class ClosedMonthGuardedCalcStore implements CommCalcStore {

    private final CommCalcStore delegate;
    private final SettleCloseStore closeStore;

    public ClosedMonthGuardedCalcStore(CommCalcStore delegate, SettleCloseStore closeStore) {
        this.delegate = Objects.requireNonNull(delegate);
        this.closeStore = Objects.requireNonNull(closeStore);
    }

    @Override
    public CommCalcRecord insert(CommCalcRecord record) {
        if (closeStore.stateOf(record.closeYm()) == SettleCloseStore.CloseState.CLOSED) {
            throw new IllegalStateException(
                    "마감월(" + record.closeYm() + ")에는 레코드를 귀속시킬 수 없습니다 — "
                            + "OPEN 월로 귀속하거나 ADJUSTMENT를 사용하세요");
        }
        return delegate.insert(record);
    }

    @Override
    public Optional<CommCalcRecord> findById(long calcId) {
        return delegate.findById(calcId);
    }

    @Override
    public List<CommCalcRecord> findByEventId(long eventId) {
        return delegate.findByEventId(eventId);
    }

    @Override
    public List<CommCalcRecord> findByPolicyAndRecipient(PolicyNo policyNo, String recipientId) {
        return delegate.findByPolicyAndRecipient(policyNo, recipientId);
    }

    @Override
    public List<CommCalcRecord> findByCloseYm(CloseYm closeYm) {
        return delegate.findByCloseYm(closeYm);
    }

    @Override
    public CommCalcRecord transition(long calcId, CalcStatus to) {
        return delegate.transition(calcId, to);
    }
}
