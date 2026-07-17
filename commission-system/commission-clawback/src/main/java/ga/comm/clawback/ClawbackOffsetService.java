package ga.comm.clawback;

import ga.comm.domain.id.AgentId;
import ga.comm.domain.money.Money;

import java.util.Objects;

/**
 * 환수 상계·채권화 (설계서 §6.3, 마감 프로세스 ③에서 호출):
 * <ul>
 *   <li>당월 순지급이 음수(환수 &gt; 지급) → 부족분을 채권으로 이월</li>
 *   <li>당월 순지급이 양수 → 열린 채권을 오래된 것부터 자동 상계</li>
 * </ul>
 */
public class ClawbackOffsetService {

    private final ClawbackReceivableStore store;

    public ClawbackOffsetService(ClawbackReceivableStore store) {
        this.store = Objects.requireNonNull(store);
    }

    /**
     * 당월 순지급이 음수일 때 부족분을 채권화한다.
     *
     * @param negativeNet  음수 순지급액 (음수 Money)
     * @param originCalcId 원인 환수 calc (대표 1건, 없으면 null)
     * @return 생성된 채권
     */
    public ClawbackReceivable capitalize(AgentId agentId, Money negativeNet, Long originCalcId) {
        if (!negativeNet.isNegative()) {
            throw new IllegalArgumentException("채권화 대상은 음수 순지급이어야 합니다: " + negativeNet);
        }
        return store.create(agentId, originCalcId, negativeNet.abs());
    }

    /**
     * 가용 지급액에서 열린 채권을 상계한다 (오래된 것부터).
     *
     * @param availablePayment 이번 지급 가능액 (양수)
     * @param payingCalcId     상계가 반영되는 지급 calc ID (OFFSET_HIST 기록용)
     * @return 총 상계액 (지급액에서 차감할 금액)
     */
    public Money consume(AgentId agentId, Money availablePayment, long payingCalcId) {
        if (!availablePayment.isPositive()) {
            return Money.ZERO;
        }
        Money left = availablePayment;
        Money totalOffset = Money.ZERO;

        for (ClawbackReceivable receivable : store.findOffsettable(agentId)) {
            if (!left.isPositive()) {
                break;
            }
            Money applied = receivable.offset(left);
            if (applied.isPositive()) {
                store.save(receivable);
                store.recordOffset(receivable.receivableId(), payingCalcId, applied);
                left = left.minus(applied);
                totalOffset = totalOffset.plus(applied);
            }
        }
        return totalOffset;
    }
}
