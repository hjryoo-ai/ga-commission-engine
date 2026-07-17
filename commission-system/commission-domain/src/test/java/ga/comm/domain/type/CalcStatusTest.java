package ga.comm.domain.type;

import org.junit.jupiter.api.Test;

import static ga.comm.domain.type.CalcStatus.CALCULATED;
import static ga.comm.domain.type.CalcStatus.CONFIRMED;
import static ga.comm.domain.type.CalcStatus.PAID;
import static ga.comm.domain.type.CalcStatus.REVERSED;
import static org.assertj.core.api.Assertions.assertThat;

class CalcStatusTest {

    @Test
    void 정상_전이_경로() {
        assertThat(CALCULATED.canTransitionTo(CONFIRMED)).isTrue();
        assertThat(CONFIRMED.canTransitionTo(PAID)).isTrue();
    }

    @Test
    void 모든_상태에서_REVERSED_가능() {
        assertThat(CALCULATED.canTransitionTo(REVERSED)).isTrue();
        assertThat(CONFIRMED.canTransitionTo(REVERSED)).isTrue();
        assertThat(PAID.canTransitionTo(REVERSED)).isTrue();
    }

    @Test
    void 역방향_전이는_금지() {
        assertThat(CONFIRMED.canTransitionTo(CALCULATED)).isFalse();
        assertThat(PAID.canTransitionTo(CONFIRMED)).isFalse();
        assertThat(REVERSED.canTransitionTo(CALCULATED)).isFalse();
        assertThat(CALCULATED.canTransitionTo(PAID)).isFalse();
    }
}
