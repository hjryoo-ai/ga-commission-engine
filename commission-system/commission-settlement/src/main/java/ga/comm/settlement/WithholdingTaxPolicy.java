package ga.comm.settlement;

import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.domain.money.RoundingPolicy;

import java.util.Objects;

/**
 * 사업소득 원천징수 (설계서 §2.4) — 소득세 3% + 지방소득세 0.3% (세율은 파라미터).
 *
 * <p>절사 단위(원 단위/10원 미만 절사 등)는 세법·사규 확인 대상이며, 이 클래스 교체로 반영한다.
 * 현재 규칙: 소득세·지방소득세 각각 원 단위 절사.
 */
public class WithholdingTaxPolicy {

    public static final WithholdingTaxPolicy STANDARD_3_3 =
            new WithholdingTaxPolicy(Rate.of("0.03"), Rate.of("0.003"));

    private final Rate incomeTaxRate;
    private final Rate localTaxRate;

    public WithholdingTaxPolicy(Rate incomeTaxRate, Rate localTaxRate) {
        this.incomeTaxRate = Objects.requireNonNull(incomeTaxRate);
        this.localTaxRate = Objects.requireNonNull(localTaxRate);
    }

    public record Withholding(Money incomeTax, Money localTax) {
        public Money total() {
            return incomeTax.plus(localTax);
        }
    }

    public Withholding taxOn(Money payable) {
        if (!payable.isPositive()) {
            return new Withholding(Money.ZERO, Money.ZERO);
        }
        Money incomeTax = payable.multiply(incomeTaxRate, RoundingPolicy.KRW_FLOOR);
        Money localTax = payable.multiply(localTaxRate, RoundingPolicy.KRW_FLOOR);
        return new Withholding(incomeTax, localTax);
    }
}
