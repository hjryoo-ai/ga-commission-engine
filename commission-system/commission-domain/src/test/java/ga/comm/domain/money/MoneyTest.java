package ga.comm.domain.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Nested
    @DisplayName("생성")
    class Creation {

        @Test
        void 정수_원단위로_생성한다() {
            assertThat(Money.won(1_000).toLong()).isEqualTo(1_000L);
            assertThat(Money.of(new BigDecimal("300000")).toLong()).isEqualTo(300_000L);
        }

        @Test
        void 소수부가_있으면_거부한다() {
            assertThatThrownBy(() -> Money.of(new BigDecimal("100.5")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 소수점_이하가_0이면_허용한다() {
            assertThat(Money.of(new BigDecimal("100.00"))).isEqualTo(Money.won(100));
        }

        @Test
        void 음수도_생성_가능하다_환수용() {
            assertThat(Money.won(-500).isNegative()).isTrue();
        }
    }

    @Nested
    @DisplayName("산술")
    class Arithmetic {

        @Test
        void 더하기_빼기_부호반전() {
            Money a = Money.won(1_000);
            Money b = Money.won(300);
            assertThat(a.plus(b)).isEqualTo(Money.won(1_300));
            assertThat(a.minus(b)).isEqualTo(Money.won(700));
            assertThat(a.negate()).isEqualTo(Money.won(-1_000));
            assertThat(Money.won(-700).abs()).isEqualTo(Money.won(700));
        }

        @Test
        void min_max() {
            assertThat(Money.won(100).min(Money.won(200))).isEqualTo(Money.won(100));
            assertThat(Money.won(100).max(Money.won(200))).isEqualTo(Money.won(200));
        }

        @Test
        void 비교_연산() {
            assertThat(Money.won(100).isLessThan(Money.won(101))).isTrue();
            assertThat(Money.won(100).isGreaterThanOrEqual(Money.won(100))).isTrue();
            assertThat(Money.ZERO.isZero()).isTrue();
        }
    }

    @Nested
    @DisplayName("요율 곱셈 + 반올림 정책")
    class MultiplyAndRounding {

        @Test
        void 절사_정책은_소수부를_버린다() {
            // 100,000 × 0.333333 = 33,333.3 → 33,333
            Money result = Money.won(100_000).multiply(Rate.of("0.333333"), RoundingPolicy.KRW_FLOOR);
            assertThat(result).isEqualTo(Money.won(33_333));
        }

        @Test
        void 반올림_정책은_5이상을_올린다() {
            // 100 × 0.335 = 33.5 → 34
            Money result = Money.won(100).multiply(Rate.of("0.335"), RoundingPolicy.KRW_HALF_UP);
            assertThat(result).isEqualTo(Money.won(34));

            // 100 × 0.334 = 33.4 → 33
            Money result2 = Money.won(100).multiply(Rate.of("0.334"), RoundingPolicy.KRW_HALF_UP);
            assertThat(result2).isEqualTo(Money.won(33));
        }

        @Test
        void 같은_계산이라도_정책에_따라_결과가_다르다() {
            Money base = Money.won(999);
            Rate rate = Rate.of("0.905");
            // 999 × 0.905 = 904.095
            assertThat(base.multiply(rate, RoundingPolicy.KRW_FLOOR)).isEqualTo(Money.won(904));
            assertThat(base.multiply(rate, RoundingPolicy.KRW_HALF_UP)).isEqualTo(Money.won(904));

            Rate rate2 = Rate.of("0.9055");
            // 999 × 0.9055 = 904.5945 → FLOOR 904, HALF_UP 905
            assertThat(base.multiply(rate2, RoundingPolicy.KRW_FLOOR)).isEqualTo(Money.won(904));
            assertThat(base.multiply(rate2, RoundingPolicy.KRW_HALF_UP)).isEqualTo(Money.won(905));
        }

        @Test
        void 한도_배수_곱셈_1200퍼센트() {
            // 300,000 × 12.00 = 3,600,000 — 설계서 부록 A 시나리오
            Money limit = Money.won(300_000).multiply(new BigDecimal("12.00"), RoundingPolicy.KRW_FLOOR);
            assertThat(limit).isEqualTo(Money.won(3_600_000));
        }
    }

    @Test
    void 등가성은_금액_기준이다() {
        assertThat(Money.won(100)).isEqualTo(Money.of(new BigDecimal("100")));
        assertThat(Money.won(100).hashCode()).isEqualTo(Money.of(new BigDecimal("100.00")).hashCode());
    }
}
