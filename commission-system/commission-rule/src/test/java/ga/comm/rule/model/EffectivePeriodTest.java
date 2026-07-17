package ga.comm.rule.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EffectivePeriodTest {

    @Test
    void 양끝_포함이다() {
        EffectivePeriod p = EffectivePeriod.of(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31));
        assertThat(p.contains(LocalDate.of(2026, 6, 30))).isFalse();
        assertThat(p.contains(LocalDate.of(2026, 7, 1))).isTrue();
        assertThat(p.contains(LocalDate.of(2026, 12, 31))).isTrue();
        assertThat(p.contains(LocalDate.of(2027, 1, 1))).isFalse();
    }

    @Test
    void 열린_기간은_9999년까지() {
        EffectivePeriod p = EffectivePeriod.from(LocalDate.of(2026, 1, 1));
        assertThat(p.contains(LocalDate.of(9999, 12, 31))).isTrue();
    }

    @Test
    void 시작일이_종료일보다_늦으면_거부() {
        assertThatThrownBy(() -> EffectivePeriod.of(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 겹침_판정() {
        EffectivePeriod a = EffectivePeriod.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));
        EffectivePeriod b = EffectivePeriod.of(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 12, 31));
        EffectivePeriod c = EffectivePeriod.of(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31));
        assertThat(a.overlaps(b)).isTrue();
        assertThat(a.overlaps(c)).isFalse();
    }

    @Test
    void 신규_개시일_전날로_트리밍() {
        EffectivePeriod p = EffectivePeriod.from(LocalDate.of(2026, 1, 1));
        EffectivePeriod trimmed = p.truncatedBefore(LocalDate.of(2026, 9, 1));
        assertThat(trimmed.applyTo()).isEqualTo(LocalDate.of(2026, 8, 31));
    }
}
