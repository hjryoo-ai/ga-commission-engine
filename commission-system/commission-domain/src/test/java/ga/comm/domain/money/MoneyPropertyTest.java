package ga.comm.domain.money;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.LongRange;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Money 산술 불변식 (property-based). */
class MoneyPropertyTest {

    @Property
    void 덧셈은_교환법칙을_만족한다(
            @ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long a,
            @ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long b) {
        assertThat(Money.won(a).plus(Money.won(b))).isEqualTo(Money.won(b).plus(Money.won(a)));
    }

    @Property
    void 빼기는_덧셈의_역원이다(
            @ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long a,
            @ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long b) {
        Money ma = Money.won(a);
        Money mb = Money.won(b);
        assertThat(ma.plus(mb).minus(mb)).isEqualTo(ma);
    }

    @Property
    void 절사는_참값을_넘지_않고_오차는_1원_미만이다(
            @ForAll @LongRange(min = 0, max = 1_000_000_000L) long amount,
            @ForAll @BigRange(min = "0", max = "3") @net.jqwik.api.constraints.Scale(6) BigDecimal rateValue) {
        Rate rate = Rate.of(rateValue);
        BigDecimal exact = BigDecimal.valueOf(amount).multiply(rate.value());
        Money floored = Money.won(amount).multiply(rate, RoundingPolicy.KRW_FLOOR);

        assertThat(floored.asBigDecimal()).isLessThanOrEqualTo(exact);
        assertThat(exact.subtract(floored.asBigDecimal())).isLessThan(BigDecimal.ONE);
    }

    @Property
    void 반올림_오차는_절반원_이하다(
            @ForAll @LongRange(min = 0, max = 1_000_000_000L) long amount,
            @ForAll @BigRange(min = "0", max = "3") @net.jqwik.api.constraints.Scale(6) BigDecimal rateValue) {
        Rate rate = Rate.of(rateValue);
        BigDecimal exact = BigDecimal.valueOf(amount).multiply(rate.value());
        Money rounded = Money.won(amount).multiply(rate, RoundingPolicy.KRW_HALF_UP);

        assertThat(exact.subtract(rounded.asBigDecimal()).abs())
                .isLessThanOrEqualTo(new BigDecimal("0.5"));
    }

    @Property
    void negate는_두번_적용하면_원래대로(@ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long a) {
        assertThat(Money.won(a).negate().negate()).isEqualTo(Money.won(a));
    }
}
