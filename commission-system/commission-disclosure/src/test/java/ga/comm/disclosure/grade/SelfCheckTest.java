package ga.comm.disclosure.grade;

import ga.comm.disclosure.grade.policy.TieBreak;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 자기 검증 규칙 각각이 위반을 잡는다(정상 응답은 통과). */
class SelfCheckTest {

    private static GradeResult.Ok ok(String key, int ordinal, int rank, boolean tie) {
        return new GradeResult.Ok(key, "1.00", "G" + ordinal, "L", ordinal, rank, tie);
    }

    @Test
    void 정상() {
        assertThatCode(() -> SelfCheck.verify(List.of(ok("a", 1, 1, false), ok("b", 2, 2, true), ok("c", 2, 2, true),
                ok("d", 3, 4, false), new GradeResult.Unavailable("e", "X")), TieBreak.SHARED_RANK)).doesNotThrowAnyException();
        assertThatCode(() -> SelfCheck.verify(List.of(ok("a", 1, 1, false), ok("b", 2, 2, true), ok("c", 2, 3, true)),
                TieBreak.STRICT)).doesNotThrowAnyException();
    }

    @Test
    void 순위가_오르는데_ordinal이_내려가면_실패() {
        assertThatThrownBy(() -> SelfCheck.verify(List.of(ok("a", 3, 1, false), ok("b", 2, 2, false)), TieBreak.STRICT))
                .isInstanceOf(PolicySelfCheckException.class).hasMessageContaining("gradeOrdinal 2 < 3");
    }

    @Test
    void 동순위인데_ordinal이_다르면_실패() {
        assertThatThrownBy(() -> SelfCheck.verify(List.of(ok("a", 1, 1, true), ok("b", 2, 1, true)), TieBreak.SHARED_RANK))
                .isInstanceOf(PolicySelfCheckException.class).hasMessageContaining("different gradeOrdinals");
    }

    @Test
    void 순위_구조_위반() {
        assertThatThrownBy(() -> SelfCheck.verify(List.of(ok("a", 1, 1, false), ok("b", 1, 3, false)), TieBreak.STRICT))
                .isInstanceOf(PolicySelfCheckException.class).hasMessageContaining("permutation");
        assertThatThrownBy(() -> SelfCheck.verify(List.of(ok("a", 1, 1, false), ok("b", 1, 3, false)), TieBreak.SHARED_RANK))
                .isInstanceOf(PolicySelfCheckException.class).hasMessageContaining("competition");
        assertThatThrownBy(() -> SelfCheck.verify(List.of(ok("a", 1, 1, true), ok("b", 1, 1, false)), TieBreak.SHARED_RANK))
                .isInstanceOf(PolicySelfCheckException.class).hasMessageContaining("is not tie");
    }
}
