package ga.comm.domain.money;

import ga.comm.domain.testing.SeededCases;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static ga.comm.domain.testing.SeededCases.longIn;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Money 산술 불변식 (property-based). 시드 고정 생성기 — jqwik 대체(Phase E3-0).
 * 각 속성: 경계값 케이스 + 무작위 {@value SeededCases#DEFAULT_COUNT}건(jqwik 기본 시행 수와 같다).
 */
class MoneyPropertyTest {

    private static final long MIN = -1_000_000_000L;
    private static final long MAX = 1_000_000_000L;

    static Stream<Arguments> pairs() {
        return SeededCases.withEdges(0x5EED_E301L, SeededCases.DEFAULT_COUNT,
                List.of(new Object[] {MIN, MIN}, new Object[] {MIN, MAX}, new Object[] {MAX, MAX}, new Object[] {0L, 0L},
                        new Object[] {0L, MAX}, new Object[] {-1L, 1L}),
                r -> new Object[] {longIn(r, MIN, MAX), longIn(r, MIN, MAX)});
    }

    static Stream<Arguments> singles() {
        return SeededCases.withEdges(0x5EED_E302L, SeededCases.DEFAULT_COUNT,
                List.of(new Object[] {MIN}, new Object[] {MAX}, new Object[] {0L}, new Object[] {-1L}, new Object[] {1L}),
                r -> new Object[] {longIn(r, MIN, MAX)});
    }

    /** amount ∈ [0, 1e9], rate ∈ [0, 3] scale 6 (jqwik {@code @BigRange(0,3) @Scale(6)}와 같은 정의역). */
    static Stream<Arguments> amountAndRate() {
        return SeededCases.withEdges(0x5EED_E303L, SeededCases.DEFAULT_COUNT,
                List.of(new Object[] {0L, rate(0)}, new Object[] {MAX, rate(3_000_000)}, new Object[] {MAX, rate(0)},
                        new Object[] {0L, rate(3_000_000)}, new Object[] {1L, rate(1)}, new Object[] {MAX, rate(1)},
                        new Object[] {999_999_999L, rate(2_999_999)}),
                r -> new Object[] {longIn(r, 0, MAX), rate(longIn(r, 0, 3_000_000))});
    }

    private static BigDecimal rate(long micro) {
        return BigDecimal.valueOf(micro, 6);
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void 덧셈은_교환법칙을_만족한다(long a, long b) {
        assertThat(Money.won(a).plus(Money.won(b))).isEqualTo(Money.won(b).plus(Money.won(a)));
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void 빼기는_덧셈의_역원이다(long a, long b) {
        Money ma = Money.won(a);
        Money mb = Money.won(b);
        assertThat(ma.plus(mb).minus(mb)).isEqualTo(ma);
    }

    @ParameterizedTest
    @MethodSource("amountAndRate")
    void 절사는_참값을_넘지_않고_오차는_1원_미만이다(long amount, BigDecimal rateValue) {
        Rate rate = Rate.of(rateValue);
        BigDecimal exact = BigDecimal.valueOf(amount).multiply(rate.value());
        Money floored = Money.won(amount).multiply(rate, RoundingPolicy.KRW_FLOOR);

        assertThat(floored.asBigDecimal()).isLessThanOrEqualTo(exact);
        assertThat(exact.subtract(floored.asBigDecimal())).isLessThan(BigDecimal.ONE);
    }

    @ParameterizedTest
    @MethodSource("amountAndRate")
    void 반올림_오차는_절반원_이하다(long amount, BigDecimal rateValue) {
        Rate rate = Rate.of(rateValue);
        BigDecimal exact = BigDecimal.valueOf(amount).multiply(rate.value());
        Money rounded = Money.won(amount).multiply(rate, RoundingPolicy.KRW_HALF_UP);

        assertThat(exact.subtract(rounded.asBigDecimal()).abs())
                .isLessThanOrEqualTo(new BigDecimal("0.5"));
    }

    @ParameterizedTest
    @MethodSource("singles")
    void negate는_두번_적용하면_원래대로(long a) {
        assertThat(Money.won(a).negate().negate()).isEqualTo(Money.won(a));
    }
}
