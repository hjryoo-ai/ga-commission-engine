package ga.comm.clawback;

import ga.comm.domain.id.AgentId;
import ga.comm.domain.money.Money;

import java.util.Objects;

/**
 * 환수 채권 (CLAWBACK_RECEIVABLE) — 당월 상계로 부족한 환수액의 이월분.
 * 이후 지급에서 자동 상계되며, 해촉자 채권은 별도 회수 프로세스로 넘긴다(범위 외).
 */
public final class ClawbackReceivable {

    private final long receivableId;
    private final AgentId agentId;
    private final Long originCalcId;
    private final Money amount;
    private Money remaining;
    private ReceivableStatus status;

    public ClawbackReceivable(long receivableId, AgentId agentId, Long originCalcId, Money amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("채권 금액은 양수여야 합니다: " + amount);
        }
        this.receivableId = receivableId;
        this.agentId = Objects.requireNonNull(agentId);
        this.originCalcId = originCalcId;
        this.amount = amount;
        this.remaining = amount;
        this.status = ReceivableStatus.OPEN;
    }

    /** DB 재적재(rehydrate) — 저장된 잔액/상태 그대로 복원한다. */
    public static ClawbackReceivable rehydrate(long receivableId, AgentId agentId, Long originCalcId,
                                               Money amount, Money remaining, ReceivableStatus status) {
        ClawbackReceivable receivable = new ClawbackReceivable(receivableId, agentId, originCalcId, amount);
        if (remaining.isNegative() || remaining.isGreaterThan(amount)) {
            throw new IllegalStateException(
                    "채권 잔액이 손상됐습니다: id=" + receivableId + " amount=" + amount + " remaining=" + remaining);
        }
        receivable.remaining = Objects.requireNonNull(remaining);
        receivable.status = Objects.requireNonNull(status);
        return receivable;
    }

    /** 가용 지급액에서 상계. 실제 상계된 금액을 반환한다. */
    public Money offset(Money available) {
        if (status == ReceivableStatus.CLOSED || status == ReceivableStatus.WRITTEN_OFF) {
            return Money.ZERO;
        }
        Money applied = remaining.min(available).max(Money.ZERO);
        if (applied.isPositive()) {
            remaining = remaining.minus(applied);
            status = remaining.isZero() ? ReceivableStatus.CLOSED : ReceivableStatus.OFFSET;
        }
        return applied;
    }

    public void writeOff() {
        if (status == ReceivableStatus.CLOSED) {
            throw new IllegalStateException("이미 종결된 채권입니다: " + receivableId);
        }
        status = ReceivableStatus.WRITTEN_OFF;
    }

    public long receivableId() {
        return receivableId;
    }

    public AgentId agentId() {
        return agentId;
    }

    public Long originCalcId() {
        return originCalcId;
    }

    public Money amount() {
        return amount;
    }

    public Money remaining() {
        return remaining;
    }

    public ReceivableStatus status() {
        return status;
    }
}
