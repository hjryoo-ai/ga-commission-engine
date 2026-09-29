package ga.comm.infra.it;

import ga.comm.disclosure.grade.fixture.DisclosurePolicyRepositoryContract;
import ga.comm.disclosure.grade.fixture.PolicyFixtures;
import ga.comm.disclosure.grade.policy.DisclosurePolicyRepository;
import ga.comm.infra.OraclePersistence;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** E3 계약 스위트 — Oracle 어댑터. 더해 같은 apply_from의 ACTIVE 중복을 DDL(V103 함수 기반 유니크)이 막는지 본다. */
class OracleDisclosurePolicyRepositoryIT extends DisclosurePolicyRepositoryContract {

    private final OraclePersistence persistence = OracleTestSupport.persistence();
    private final DisclosureSeed seed = new DisclosureSeed(OracleTestSupport.dataSource());

    @Override
    protected DisclosurePolicyRepository repository() {
        DisclosurePolicyRepository inner = persistence.disclosurePolicyRepository();
        return new DisclosurePolicyRepository() {
            @Override
            public java.util.List<ga.comm.disclosure.grade.policy.PolicyRow> activeGradingPolicies(LocalDate asOf) {
                return persistence.inTx(() -> inner.activeGradingPolicies(asOf));
            }

            @Override
            public java.util.List<ga.comm.disclosure.grade.policy.PolicyRow> activeRankingPolicies(LocalDate asOf) {
                return persistence.inTx(() -> inner.activeRankingPolicies(asOf));
            }
        };
    }

    @Override
    protected void clear() {
        OracleTestSupport.cleanAll();
    }

    @Override
    protected void insertGrading(String id, LocalDate from, LocalDate to, String status, String body) {
        seed.policy("DISC_GRADING_POLICY", id, from, to, status, body);
    }

    @Override
    protected void insertRanking(String id, LocalDate from, LocalDate to, String status, String body) {
        seed.policy("DISC_RANKING_POLICY", id, from, to, status, body);
    }

    @Test
    void 같은_apply_from의_ACTIVE_중복은_DDL이_거부한다() {
        seed.grading("GRADING-X", D2026_07_01, null, "ACTIVE", PolicyFixtures.GRADING_5);
        assertThatThrownBy(() -> seed.grading("GRADING-Y", D2026_07_01, null, "ACTIVE", PolicyFixtures.GRADING_4))
                .hasMessageContaining("UX_DISC_GRADING_ACTIVE");
    }

    @Test
    void 정책_body는_JSON이어야_한다() {
        assertThatThrownBy(() -> seed.policy("DISC_RANKING_POLICY", "RANK-BAD", D2026_07_01, null, "DRAFT", "not json"))
                .hasMessageContaining("CK_DISC_RANKING_BODY_JSON");
    }
}
