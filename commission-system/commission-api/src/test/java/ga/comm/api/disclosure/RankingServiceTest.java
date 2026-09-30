package ga.comm.api.disclosure;

import ga.comm.disclosure.grade.fixture.PolicyFixtures;
import ga.comm.disclosure.grade.policy.PolicyLoader;
import ga.comm.disclosure.grade.policy.RankingPolicySpec;
import ga.comm.disclosure.grade.rank.SetRanker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E4: 세트 내 순위·동점 정책(데이터). 측정값 {0.50, 0.70, 0.70, 0.70, 0.90} — 동값 3개.
 * SHARED_RANK → 1-2-2-2-5, 동값 3개 전부 tie. STRICT(2차 키 productKey) → 1..5 순열, 분리된 3개는 tie=true(원래 동값).
 */
class RankingServiceTest {

    private static final RankingService SERVICE = new RankingService();

    private static List<SetRanker.Candidate> fiveWithTripleTie() {
        return List.of(
                new SetRanker.Candidate("INS-E:P5", new BigDecimal("0.900000")),
                new SetRanker.Candidate("INS-C:P3", new BigDecimal("0.70")),
                new SetRanker.Candidate("INS-A:P1", new BigDecimal("0.500000")),
                new SetRanker.Candidate("INS-D:P4", new BigDecimal("0.700000")),
                new SetRanker.Candidate("INS-B:P2", new BigDecimal("0.7")));
    }

    private static RankingPolicySpec policy(String fixture) {
        return PolicyLoader.ranking(PolicyFixtures.read(fixture));
    }

    @Test
    void SHARED_RANK는_경쟁_순위_1_2_2_2_5_동값은_전부_tie() {
        List<SetRanker.Ranked> ranked = SERVICE.rankInSet(fiveWithTripleTie(), policy(PolicyFixtures.RANKING_SHARED));
        assertThat(ranked).extracting(SetRanker.Ranked::rank).containsExactly(1, 2, 2, 2, 5);
        assertThat(ranked).extracting(SetRanker.Ranked::tie).containsExactly(false, true, true, true, false);
        assertThat(ranked).extracting(SetRanker.Ranked::productKey)
                .containsExactly("INS-A:P1", "INS-B:P2", "INS-C:P3", "INS-D:P4", "INS-E:P5");
    }

    @Test
    void STRICT는_2차_키로_1부터_m_순열_분리된_항목은_tie() {
        List<SetRanker.Ranked> ranked = SERVICE.rankInSet(fiveWithTripleTie(), policy(PolicyFixtures.RANKING_STRICT));
        assertThat(ranked).extracting(SetRanker.Ranked::rank).containsExactly(1, 2, 3, 4, 5);
        assertThat(ranked).extracting(SetRanker.Ranked::tie).containsExactly(false, true, true, true, false);
        // 2차 키 PRODUCT_KEY_ASC: 동값 3개는 productKey 사전순으로 2·3·4
        assertThat(ranked).extracting(SetRanker.Ranked::productKey)
                .containsExactly("INS-A:P1", "INS-B:P2", "INS-C:P3", "INS-D:P4", "INS-E:P5");
    }

    @Test
    void 측정값_비교는_스케일_무관_값_비교() {
        // "0.70"과 "0.700000"과 "0.7"은 같은 값이다(BigDecimal.compareTo)
        List<SetRanker.Ranked> ranked = SERVICE.rankInSet(List.of(
                new SetRanker.Candidate("K:1", new BigDecimal("0.70")), new SetRanker.Candidate("K:2", new BigDecimal("0.7"))),
                policy(PolicyFixtures.RANKING_SHARED));
        assertThat(ranked).extracting(SetRanker.Ranked::rank).containsExactly(1, 1);
    }

    @Test
    void 빈_세트와_단일_항목() {
        assertThat(SERVICE.rankInSet(List.of(), policy(PolicyFixtures.RANKING_STRICT))).isEmpty();
        assertThat(SERVICE.rankInSet(List.of(new SetRanker.Candidate("K:1", BigDecimal.ONE)), policy(PolicyFixtures.RANKING_SHARED)))
                .extracting(SetRanker.Ranked::rank, SetRanker.Ranked::tie).containsExactly(org.assertj.core.groups.Tuple.tuple(1, false));
    }

    @Test
    void 삼분위_정수식은_기존_올림식과_같다() {
        // ⌈n/3⌉ = (n+2)/3. 기존 테스트(DisclosureServiceTest)의 A/B/C 기대값이 그대로이고, 여기서는 경계를 직접 확인한다.
        TercileGradingPolicy tercile = new TercileGradingPolicy();
        for (int total = 1; total <= 300; total++) {
            int third = 0;
            while (third * 3 < total) {
                third++;
            }
            third = Math.max(1, third);
            assertThat(tercile.grade(third, total, null)).isEqualTo("A");
            if (third + 1 <= total) {
                assertThat(tercile.grade(third + 1, total, null)).isEqualTo(third + 1 <= 2 * third ? "B" : "C");
            }
        }
    }
}
