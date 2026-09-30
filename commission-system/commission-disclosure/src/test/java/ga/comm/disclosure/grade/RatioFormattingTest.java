package ga.comm.disclosure.grade;

import com.fasterxml.jackson.databind.JsonNode;
import ga.comm.disclosure.grade.fixture.GradeScenario;
import ga.comm.disclosure.grade.fixture.PolicyFixtures;
import ga.comm.disclosure.grade.snapshot.SnapshotJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E9: ratioToAvg는 정책의 ratioScale·ratioRounding대로 <b>한 번</b> 만들어지고 응답·스냅샷·재조회가 같은 문자열을 나른다.
 * 모집단 {0.845, 1.000, 1.155} → Σ=3, n=3 → 비율이 요율 그대로라 x.xx5 경계가 반올림 모드를 드러낸다.
 */
class RatioFormattingTest {

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource({
            "0.845000, 3, 1.000000, HALF_UP,   0.85",
            "0.845000, 3, 1.000000, HALF_EVEN, 0.84",
            "0.845000, 3, 1.000000, DOWN,      0.84",
            "1.155000, 3, 1.000000, HALF_EVEN, 1.16",
            "1.000000, 3, 1.000000, HALF_UP,   1.00",
    })
    void 한_번의_나눗셈_한_번의_반올림(String measure, int n, String avg, RoundingMode mode, String expected) {
        BigDecimal m = new BigDecimal(measure);
        BigDecimal sum = new BigDecimal(avg).multiply(BigDecimal.valueOf(n));
        assertThat(RatioToAvg.of(m, n, sum, 2, mode).text()).isEqualTo(expected);
    }

    @Test
    void 이중_반올림이_없다() {
        // 평균을 먼저 2자리로 반올림하면 1/3 → 0.33이 되어 비율이 1.01로 틀어진다. 한 번의 나눗셈은 1.00.
        BigDecimal third = new BigDecimal("0.333333");
        assertThat(RatioToAvg.of(third, 3, new BigDecimal("0.999999"), 2, RoundingMode.HALF_UP).text()).isEqualTo("1.00");
    }

    @ParameterizedTest(name = "{0} scale {1} → {2}/{3}/{4}")
    @CsvSource({
            "HALF_UP,   2, 0.85, 1.00, 1.16",
            "HALF_EVEN, 2, 0.84, 1.00, 1.16",
            "HALF_UP,   3, 0.845, 1.000, 1.155",
    })
    void 응답_스냅샷_재조회_삼자_동일(RoundingMode mode, int scale, String a, String b, String c) throws Exception {
        GradeScenario s = new GradeScenario().ranking(PolicyFixtures.RANKING_SHARED);
        s.policies.addGrading("GRADING-R", java.time.LocalDate.of(2026, 7, 1), null, "ACTIVE",
                PolicyFixtures.read(PolicyFixtures.GRADING_5).replace("\"ratioRounding\": \"HALF_UP\"", "\"ratioRounding\": \"" + mode + "\"")
                        .replace("\"ratioScale\": 2", "\"ratioScale\": " + scale));
        s.product("INS-A:PRD-1", "0.845000").product("INS-B:PRD-2", "1.000000").product("INS-C:PRD-3", "1.155000");
        DisclosureGradeService service = s.service();
        DisclosureGradeService.Issued issued = service.issue(GradeScenario.request("INS-A:PRD-1", "INS-B:PRD-2", "INS-C:PRD-3"));

        Map<String, String> response = ratios(SnapshotJson.mapper().readTree(issued.responseCanonical()));
        Map<String, String> stored = new HashMap<>();
        s.snapshots.snapshot(issued.snapshotId()).results().forEach(r -> stored.put(r.productKey(), ((GradeResult.Ok) r).ratioToAvg()));
        Map<String, String> refetched = ratios(SnapshotJson.mapper().readTree(service.refetch(issued.snapshotId())));

        assertThat(response).containsExactlyInAnyOrderEntriesOf(Map.of("INS-A:PRD-1", a, "INS-B:PRD-2", b, "INS-C:PRD-3", c));
        assertThat(stored).isEqualTo(response);
        assertThat(refetched).isEqualTo(response);
        // 응답 JSON에서 ratioToAvg는 문자열이다(number 아님)
        assertThat(issued.responseCanonical()).contains("\"ratioToAvg\":\"" + a + "\"");
    }

    private static Map<String, String> ratios(JsonNode response) {
        Map<String, String> map = new HashMap<>();
        response.get("results").forEach(n -> {
            assertThat(n.get("ratioToAvg").isTextual()).isTrue();
            map.put(n.get("productKey").asText(), n.get("ratioToAvg").textValue());
        });
        return map;
    }
}
