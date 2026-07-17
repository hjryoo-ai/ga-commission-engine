package ga.comm.domain.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateTest {

    @Test
    void 소수_비율로_생성한다() {
        assertThat(Rate.of("0.9").value()).isEqualByComparingTo("0.9");
        assertThat(Rate.of("3.0").value()).isEqualByComparingTo("3");
    }

    @Test
    void 퍼센트_표기로도_생성한다() {
        assertThat(Rate.percent("90")).isEqualTo(Rate.of("0.90"));
        assertThat(Rate.percent("1200")).isEqualTo(Rate.of("12"));
    }

    @Test
    void 음수는_거부한다() {
        assertThatThrownBy(() -> Rate.of("-0.1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scale_6자리_초과는_거부한다() {
        assertThatThrownBy(() -> Rate.of("0.1234567")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 정수부_3자리_초과는_거부한다_NUMBER_9_6() {
        assertThatThrownBy(() -> Rate.of(new BigDecimal("1000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Rate.of(new BigDecimal("999.999999")).value()).isEqualByComparingTo("999.999999");
    }

    @Test
    void 등가성은_수치_기준이다() {
        assertThat(Rate.of("0.9")).isEqualTo(Rate.of("0.900000"));
    }
}
