package ga.comm.disclosure.grade;

import com.fasterxml.jackson.databind.JsonNode;
import ga.comm.disclosure.grade.fixture.GradeScenario;
import ga.comm.disclosure.grade.fixture.PolicyFixtures;
import ga.comm.disclosure.grade.snapshot.SnapshotJson;
import ga.comm.domain.testing.SeededCases;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;

import static ga.comm.domain.testing.SeededCases.longIn;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E5: 순위 오름차순 ⇒ gradeOrdinal 비감소, 동순위 ⇒ gradeOrdinal 동일 — 시드 고정 무작위 세트 500건(jqwik 금지, SeededCases).
 * 서비스의 자기 검증과 독립적으로 응답 JSON만 보고 다시 판정한다. 동점이 자주 나오도록 요율을 좁은 격자에서 뽑고,
 * 등급 정책 2종 × 동점 규칙 2종을 섞는다. STRICT는 1..m 순열, SHARED_RANK는 경쟁 순위와 동순위 tie도 함께 본다.
 */
class MonotonicityPropertyTest {

    private static final long SEED = 0x5EED_E305L;

    record Case(String grading, String ranking, List<String> rates, List<Integer> requested, int outsiders) {
    }

    static Stream<Arguments> sets() {
        return SeededCases.of(SEED, 500, r -> new Object[] {randomCase(r)});
    }

    private static Case randomCase(RandomGenerator r) {
        int members = (int) longIn(r, 3, 20);
        List<String> rates = new ArrayList<>();
        for (int i = 0; i < members; i++) {
            // 0.40 ~ 1.80 사이 0.05 격자 → 동값·경계값이 자주 나온다
            rates.add(BigDecimal.valueOf(longIn(r, 8, 36) * 5, 2).toPlainString());
        }
        List<Integer> requested = new ArrayList<>();
        int size = (int) longIn(r, 1, Math.min(10, members));
        while (requested.size() < size) {
            int pick = (int) longIn(r, 0, members - 1);
            if (!requested.contains(pick)) {
                requested.add(pick);
            }
        }
        return new Case(r.nextBoolean() ? PolicyFixtures.GRADING_5 : PolicyFixtures.GRADING_4,
                r.nextBoolean() ? PolicyFixtures.RANKING_SHARED : PolicyFixtures.RANKING_STRICT,
                rates, requested, (int) longIn(r, 0, 2));
    }

    @ParameterizedTest
    @MethodSource("sets")
    void 순위와_등급은_단조다(Case c) throws Exception {
        GradeScenario s = GradeScenario.standard(c.grading(), c.ranking());
        for (int i = 0; i < c.rates().size(); i++) {
            s.product(key(i), c.rates().get(i));
        }
        List<String> keys = new ArrayList<>(c.requested().stream().map(MonotonicityPropertyTest::key).toList());
        for (int i = 0; i < c.outsiders(); i++) {
            keys.add("OUT-" + i + ":NOT-MEMBER");
        }
        JsonNode response = SnapshotJson.mapper().readTree(
                s.service().issue(GradeScenario.request(keys.toArray(String[]::new))).responseCanonical());

        List<JsonNode> ok = new ArrayList<>();
        response.get("results").forEach(n -> {
            if (n.get("status").asText().equals("OK")) {
                ok.add(n);
            } else {
                assertThat(n.has("rankInSet") || n.has("gradeOrdinal") || n.has("ratioToAvg")).isFalse();
            }
        });
        assertThat(ok).hasSize(c.requested().size());
        ok.sort(Comparator.comparingInt(n -> n.get("rankInSet").asInt()));
        for (int i = 1; i < ok.size(); i++) {
            JsonNode prev = ok.get(i - 1);
            JsonNode cur = ok.get(i);
            assertThat(cur.get("gradeOrdinal").asInt()).as("rank order ⇒ ordinal non-decreasing")
                    .isGreaterThanOrEqualTo(prev.get("gradeOrdinal").asInt());
            if (cur.get("rankInSet").asInt() == prev.get("rankInSet").asInt()) {
                assertThat(cur.get("gradeOrdinal").asInt()).as("same rank ⇒ same ordinal").isEqualTo(prev.get("gradeOrdinal").asInt());
            }
        }
        boolean strict = response.get("tieBreak").asText().equals("STRICT");
        for (int i = 0; i < ok.size(); i++) {
            int rank = ok.get(i).get("rankInSet").asInt();
            if (strict) {
                assertThat(rank).isEqualTo(i + 1);
            } else {
                assertThat(i == 0 ? rank == 1 : rank == ok.get(i - 1).get("rankInSet").asInt() || rank == i + 1).isTrue();
            }
        }
    }

    private static String key(int i) {
        return "INS-" + i + ":PRD-" + i;
    }
}
