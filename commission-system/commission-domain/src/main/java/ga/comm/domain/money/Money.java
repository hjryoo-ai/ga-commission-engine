package ga.comm.domain.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 원(KRW) 단위 금액 값객체.
 *
 * <p>규약:
 * <ul>
 *   <li>내부는 scale 0 의 {@link BigDecimal}. double/float 기반 생성 경로는 제공하지 않는다.</li>
 *   <li>생성 후 불변. 모든 산술은 새 인스턴스를 반환한다.</li>
 *   <li>요율 곱셈 등 소수가 발생하는 연산은 반드시 {@link RoundingPolicy}를 받는다.</li>
 * </ul>
 */
public final class Money implements Comparable<Money> {

    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(0));

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        this.amount = amount;
    }

    public static Money won(long amount) {
        return new Money(BigDecimal.valueOf(amount).setScale(0));
    }

    /** 정수 원 단위 값만 허용. 소수부가 있으면 예외. */
    public static Money of(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        try {
            return new Money(amount.setScale(0, RoundingMode.UNNECESSARY));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("원 단위 정수 금액만 허용됩니다: " + amount, e);
        }
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    public Money negate() {
        return new Money(amount.negate());
    }

    public Money abs() {
        return new Money(amount.abs());
    }

    public Money min(Money other) {
        return isLessThan(other) ? this : other;
    }

    public Money max(Money other) {
        return isGreaterThan(other) ? this : other;
    }

    /** 요율 적용. 소수 발생분은 정책에 따라 즉시 원 단위로 확정한다. */
    public Money multiply(Rate rate, RoundingPolicy policy) {
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(policy, "policy");
        return new Money(policy.apply(amount.multiply(rate.value())));
    }

    /** 배수 적용 (예: 1200%룰의 12배). */
    public Money multiply(BigDecimal multiplier, RoundingPolicy policy) {
        Objects.requireNonNull(multiplier, "multiplier");
        Objects.requireNonNull(policy, "policy");
        return new Money(policy.apply(amount.multiply(multiplier)));
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isGreaterThanOrEqual(Money other) {
        return compareTo(other) >= 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    public boolean isLessThanOrEqual(Money other) {
        return compareTo(other) <= 0;
    }

    public BigDecimal asBigDecimal() {
        return amount;
    }

    public long toLong() {
        return amount.longValueExact();
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money other)) {
            return false;
        }
        return amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        return amount.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
