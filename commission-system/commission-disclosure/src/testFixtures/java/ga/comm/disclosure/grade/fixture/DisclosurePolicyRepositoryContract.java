package ga.comm.disclosure.grade.fixture;

import ga.comm.disclosure.grade.policy.DisclosurePolicyRepository;
import ga.comm.disclosure.grade.policy.PolicyResolver;
import ga.comm.disclosure.grade.policy.TieBreak;
import ga.comm.rule.AmbiguousRuleException;
import ga.comm.rule.RuleNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 정책 버전 저장소 계약(E3): 기준일 단건 해석 — 엔진 룰 규약(부록 B-3·B-13, §6.6) 재사용.
 * 같은 기준일에 ACTIVE 2건 → {@link AmbiguousRuleException}, 0건 → {@link RuleNotFoundException}. 유효기간 양끝 포함.
 * 인메모리 레퍼런스와 Oracle 어댑터가 같은 스위트를 상속한다(설계서 §8.7).
 */
public abstract class DisclosurePolicyRepositoryContract {

    protected static final LocalDate D2026_07_01 = LocalDate.of(2026, 7, 1);

    protected abstract DisclosurePolicyRepository repository();

    protected abstract void clear();

    protected abstract void insertGrading(String id, LocalDate from, LocalDate to, String status, String body);

    protected abstract void insertRanking(String id, LocalDate from, LocalDate to, String status, String body);

    private PolicyResolver resolver() {
        return new PolicyResolver(repository());
    }

    @BeforeEach
    void reset() {
        clear();
    }

    @Test
    void 기준일에_ACTIVE_1건이면_그_버전으로_해석한다_유효기간_양끝_포함() {
        insertGrading("GRADING-2026-07", D2026_07_01, LocalDate.of(2026, 12, 31), "ACTIVE", PolicyFixtures.read(PolicyFixtures.GRADING_5));
        insertRanking("RANK-2026-07", D2026_07_01, null, "ACTIVE", PolicyFixtures.read(PolicyFixtures.RANKING_SHARED));

        assertThat(resolver().grading(D2026_07_01).id()).isEqualTo("GRADING-2026-07");
        assertThat(resolver().grading(LocalDate.of(2026, 12, 31)).id()).isEqualTo("GRADING-2026-07");
        assertThat(resolver().ranking(LocalDate.of(2030, 1, 1)).spec().tieBreak()).isEqualTo(TieBreak.SHARED_RANK);
        assertThatThrownBy(() -> resolver().grading(LocalDate.of(2027, 1, 1))).isInstanceOf(RuleNotFoundException.class);
        assertThatThrownBy(() -> resolver().grading(LocalDate.of(2026, 6, 30))).isInstanceOf(RuleNotFoundException.class);
    }

    @Test
    void 같은_기준일에_ACTIVE_2건이면_Ambiguous로_실패한다() {
        insertGrading("GRADING-A", D2026_07_01, null, "ACTIVE", PolicyFixtures.read(PolicyFixtures.GRADING_5));
        insertGrading("GRADING-B", LocalDate.of(2026, 9, 1), null, "ACTIVE", PolicyFixtures.read(PolicyFixtures.GRADING_4));
        insertRanking("RANK-A", D2026_07_01, null, "ACTIVE", PolicyFixtures.read(PolicyFixtures.RANKING_SHARED));
        insertRanking("RANK-B", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 30), "ACTIVE", PolicyFixtures.read(PolicyFixtures.RANKING_STRICT));

        assertThat(resolver().grading(LocalDate.of(2026, 8, 31)).id()).isEqualTo("GRADING-A");
        assertThatThrownBy(() -> resolver().grading(LocalDate.of(2026, 9, 1))).isInstanceOf(AmbiguousRuleException.class)
                .hasMessageContaining("GRADING-A").hasMessageContaining("GRADING-B");
        assertThatThrownBy(() -> resolver().ranking(LocalDate.of(2026, 9, 30))).isInstanceOf(AmbiguousRuleException.class);
        assertThat(resolver().ranking(LocalDate.of(2026, 10, 1)).id()).isEqualTo("RANK-A");
    }

    @Test
    void ACTIVE가_아닌_버전은_해석하지_않는다_0건은_명시_실패() {
        insertGrading("GRADING-DRAFT", D2026_07_01, null, "DRAFT", PolicyFixtures.read(PolicyFixtures.GRADING_5));
        insertGrading("GRADING-OLD", D2026_07_01, null, "SUPERSEDED", PolicyFixtures.read(PolicyFixtures.GRADING_5));

        assertThatThrownBy(() -> resolver().grading(LocalDate.of(2026, 9, 23)))
                .isInstanceOf(RuleNotFoundException.class).hasMessageContaining("DISC_GRADING_POLICY");
        assertThatThrownBy(() -> resolver().ranking(LocalDate.of(2026, 9, 23)))
                .isInstanceOf(RuleNotFoundException.class).hasMessageContaining("DISC_RANKING_POLICY");
    }
}
