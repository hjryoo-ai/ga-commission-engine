package ga.comm.domain.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 생성기 자체의 결정론·경계값·이름 규약. */
class SeededCasesTest {

    @Test
    void sameSeedSameCases() {
        List<Object> a = SeededCases.of(0x5EEDL, 50, r -> new Object[] {r.nextLong()}).map(x -> x.get()[0]).toList();
        List<Object> b = SeededCases.of(0x5EEDL, 50, r -> new Object[] {r.nextLong()}).map(x -> x.get()[0]).toList();
        assertThat(a).hasSize(50).isEqualTo(b);
        List<Object> c = SeededCases.of(0x5EEEL, 50, r -> new Object[] {r.nextLong()}).map(x -> x.get()[0]).toList();
        assertThat(c).isNotEqualTo(a);
    }

    @Test
    void edgesComeFirstAndAreNamed() {
        List<Arguments> cases = SeededCases.withEdges(1L, 3, List.of(new Object[] {0L}, new Object[] {9L}),
                r -> new Object[] {r.nextLong(1, 8)}).toList();
        assertThat(cases).hasSize(5);
        assertThat(cases.get(0).get()[0]).isEqualTo(0L);
        assertThat(cases.get(1).get()[0]).isEqualTo(9L);
        assertThat(((Arguments.ArgumentSet) cases.get(0)).getName()).endsWith("edge=0");
        assertThat(((Arguments.ArgumentSet) cases.get(2)).getName()).endsWith("case=0");
    }

    @Test
    void longInIsClosedInterval() {
        var r = SeededCases.generator(7L);
        boolean sawMin = false;
        boolean sawMax = false;
        for (int i = 0; i < 10_000; i++) {
            long v = SeededCases.longIn(r, 0, 3);
            assertThat(v).isBetween(0L, 3L);
            sawMin |= v == 0;
            sawMax |= v == 3;
        }
        assertThat(sawMin && sawMax).isTrue();
    }

    @Test
    void countMustBePositive() {
        assertThatThrownBy(() -> SeededCases.of(1L, 0, r -> new Object[0])).isInstanceOf(IllegalArgumentException.class);
    }
}
