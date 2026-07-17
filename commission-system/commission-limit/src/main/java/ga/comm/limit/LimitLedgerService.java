package ga.comm.limit;

import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.rule.model.LimitRule;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/** 한도 원장 조작 서비스 — 게이트 밖에서 필요한 원장 연산(환수 차감 등)의 단일 창구. */
public class LimitLedgerService {

    private final LimitLedgerStore ledgerStore;

    public LimitLedgerService(LimitLedgerStore ledgerStore) {
        this.ledgerStore = Objects.requireNonNull(ledgerStore);
    }

    /**
     * 환수 발생 시 원장 차감 (설계서 §6.3) — 한도 여유 복원.
     * 룰의 clawbackRestores=N 이거나 이벤트일이 초년도 윈도우 밖이면 차감하지 않는다(유권해석 파라미터).
     *
     * @param clawbackCalcId 환수 COMM_CALC ID (음수 레코드)
     * @param clawedAmount   환수액 절대값 (양수)
     * @return 차감 수행 여부
     */
    public boolean deductForClawback(PolicyNo policyNo, AgentId agentId, LocalDate eventDate,
                                     LimitRule rule, long clawbackCalcId, Money clawedAmount) {
        if (!rule.clawbackRestores()) {
            return false;
        }
        Optional<LimitLedger> ledgerOpt = ledgerStore.find(policyNo, agentId);
        if (ledgerOpt.isEmpty()) {
            return false;
        }
        LimitLedger ledger = ledgerOpt.get();
        if (!ledger.inFirstYearWindow(eventDate)) {
            return false;
        }
        // 차감은 누적을 0 밑으로 내릴 수 없다 — 한도 포함분 기지급을 넘는 차감은 잘못된 호출
        Money deductible = clawedAmount.min(ledger.accumPaid());
        if (deductible.isPositive()) {
            ledger.post(clawbackCalcId, deductible.negate());
            ledgerStore.save(ledger);
        }
        return deductible.isPositive();
    }

    /**
     * 부활 시 환수분 재지급 → 원장 재가산. 잔여 한도를 넘는 부분은 가산하지 않는다
     * (재가산으로 한도 초과 상태를 만들 수는 없다).
     *
     * @return 실제 가산된 금액
     */
    public Money repostForRevive(PolicyNo policyNo, AgentId agentId, LocalDate eventDate,
                                 LimitRule rule, long repayCalcId, Money repaidAmount) {
        if (!rule.clawbackRestores()) {
            return Money.ZERO;
        }
        Optional<LimitLedger> ledgerOpt = ledgerStore.find(policyNo, agentId);
        if (ledgerOpt.isEmpty()) {
            return Money.ZERO;
        }
        LimitLedger ledger = ledgerOpt.get();
        if (!ledger.inFirstYearWindow(eventDate)) {
            return Money.ZERO;
        }
        Money postable = repaidAmount.min(ledger.available());
        if (postable.isPositive()) {
            ledger.post(repayCalcId, postable);
            ledgerStore.save(ledger);
        }
        return postable;
    }

    public Optional<LimitLedger> find(PolicyNo policyNo, AgentId agentId) {
        return ledgerStore.find(policyNo, agentId);
    }
}
