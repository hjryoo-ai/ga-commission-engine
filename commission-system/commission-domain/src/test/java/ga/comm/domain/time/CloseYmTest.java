package ga.comm.domain.time;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloseYmTest {

    @Test
    void yyyyMM_문자열과_상호변환된다() {
        assertThat(CloseYm.of("202607").value()).isEqualTo("202607");
        assertThat(CloseYm.from(LocalDate.of(2026, 7, 16)).value()).isEqualTo("202607");
    }

    @Test
    void 잘못된_형식은_거부한다() {
        assertThatThrownBy(() -> CloseYm.of("2026-07")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CloseYm.of("202613")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void 익월_전월_연산() {
        assertThat(CloseYm.of("202612").next().value()).isEqualTo("202701");
        assertThat(CloseYm.of("202701").prev().value()).isEqualTo("202612");
        assertThat(CloseYm.of("202701").plusMonths(48).value()).isEqualTo("203101");
    }

    @Test
    void 비교_연산() {
        assertThat(CloseYm.of("202607").isBefore(CloseYm.of("202608"))).isTrue();
        assertThat(CloseYm.of("202701").isAfter(CloseYm.of("202612"))).isTrue();
    }
}
