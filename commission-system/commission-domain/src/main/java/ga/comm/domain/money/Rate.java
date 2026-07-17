package ga.comm.domain.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 요율 값객체. DB 타입 NUMBER(9,6)에 대응한다 (scale 최대 6, 음수 금지).
 *
 * <p>0.9 = 90%, 3.0 = 300% 처럼 소수 비율로 표현한다. 초년도 요율은 100%를 넘을 수 있다.
 */
public final class Rate implements Comparable<Rate> {

    public static final Rate ZERO = new Rate(BigDecimal.ZERO.setScale(6));
    public static final Rate ONE = new Rate(BigDecimal.ONE.setScale(6));

    private static final int MAX_SCALE = 6;
    private static final int MAX_PRECISION_INTEGER_DIGITS = 3; // NUMBER(9,6)

    private final BigDecimal value;

    private Rate(BigDecimal value) {
        this.value = value;
    }

    public static Rate of(String value) {
        return of(new BigDecimal(value));
    }

    public static Rate of(BigDecimal value) {
        Objects.requireNonNull(value, "value");
        if (value.signum() < 0) {
            throw new IllegalArgumentException("요율은 음수일 수 없습니다: " + value);
        }
        BigDecimal scaled;
        try {
            scaled = value.setScale(MAX_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("요율 scale은 최대 6자리입니다: " + value, e);
        }
        if (scaled.precision() - scaled.scale() > MAX_PRECISION_INTEGER_DIGITS) {
            throw new IllegalArgumentException("요율 정수부는 최대 3자리입니다: " + value);
        }
        return new Rate(scaled);
    }

    /** "90" → 0.90 처럼 퍼센트 표기로 생성. */
    public static Rate percent(String percentValue) {
        return of(new BigDecimal(percentValue).movePointLeft(2));
    }

    public BigDecimal value() {
        return value;
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    @Override
    public int compareTo(Rate other) {
        return value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Rate other)) {
            return false;
        }
        return value.compareTo(other.value) == 0;
    }

    @Override
    public int hashCode() {
        return value.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}
