package ga.comm.settlement;

import ga.comm.domain.money.Money;
import ga.comm.domain.type.RecipientType;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * 지급 런의 금액 산식(설계서 §7 ③) — 순액 → 상계 → 지급가능액 → 원천세 → 실지급. 순수 함수다: 상계액은 호출자가 주는 {@code offsetter}가 정한다
 * (지급 런 = {@code ClawbackOffsetService.consume}, 잠정 명세 = {@code previewConsume} — E2 Q1). 채권화(음수 순액)는 금액만 계산하고 쓰기는
 * 호출자가 한다. {@link PayoutService#settleOne}과 {@code StatementEstimator}가 이 하나를 쓴다 — 두 경로가 갈라질 수 없게(E2 승인 §1, 주입 S13).
 */
public final class PayoutMath {

    /**
     * @param grossNet          전 상태 순합({@code NetAmountCalculator})
     * @param receivableOffset  기존 채권 상계액(설계사·양수 순액일 때만)
     * @param carriedReceivable 이월(음수 순액의 절댓값 — 설계사는 채권화, 조직은 이월 관리)
     * @param payable           지급가능액(상계 뒤, 0 이상)
     */
    public record Result(Money grossNet, Money receivableOffset, Money carriedReceivable, Money payable,
                         WithholdingTaxPolicy.Withholding tax, Money net) {
    }

    private PayoutMath() {
    }

    public static Result settle(RecipientType type, Money grossNet, UnaryOperator<Money> offsetter, WithholdingTaxPolicy taxPolicy) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(grossNet);
        Objects.requireNonNull(offsetter);
        Objects.requireNonNull(taxPolicy);
        Money offset = Money.ZERO;
        Money carried = Money.ZERO;
        Money payable = Money.ZERO;
        if (type == RecipientType.AGENT) {
            if (grossNet.isNegative()) {
                carried = grossNet.abs();
            } else if (grossNet.isPositive()) {
                offset = offsetter.apply(grossNet);
                payable = grossNet.minus(offset);
            }
        } else {
            payable = grossNet.max(Money.ZERO);
            if (grossNet.isNegative()) {
                carried = grossNet.abs();  // 조직 음수는 이월 관리 (채권화는 사규 확인)
            }
        }
        WithholdingTaxPolicy.Withholding tax = taxPolicy.taxOn(payable);
        return new Result(grossNet, offset, carried, payable, tax, payable.minus(tax.total()));
    }
}
