package ga.comm.disclosure.grade;

import com.fasterxml.jackson.databind.JsonNode;
import ga.comm.disclosure.grade.fixture.GradeScenario;
import ga.comm.disclosure.grade.fixture.PolicyFixtures;
import ga.comm.disclosure.grade.snapshot.SnapshotJson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E6: UNAVAILABLE 항목은 순위 세트에서 빠지고 6개 필드(ratioToAvg·grade·gradeLabel·gradeOrdinal·rankInSet·tie)가 <b>부재</b>다
 * (null 출력 없음). 전부 UNAVAILABLE인 세트도 정상 발급된다(컨트롤러 200은 commission-api 테스트).
 */
class GradeResponseSerializationTest {

    private static final List<String> SIX = List.of("ratioToAvg", "grade", "gradeLabel", "gradeOrdinal", "rankInSet", "tie");

    @Test
    void UNAVAILABLE은_여섯_필드가_부재이고_순위에서_빠진다() throws Exception {
        GradeScenario s = GradeScenario.standard(PolicyFixtures.GRADING_5, PolicyFixtures.RANKING_STRICT)
                .product("INS-A:PRD-1001", "0.8").product("INS-B:PRD-2044", "1.2").product("INS-C:PRD-3120", "1.0");
        String body = s.service().issue(GradeScenario.request("INS-Q:TEMP-7", "INS-B:PRD-2044", "INS-A:PRD-1001")).responseCanonical();
        JsonNode results = SnapshotJson.mapper().readTree(body).get("results");
        assertThat(body).doesNotContain("null");
        assertThat(results.get(0).get("rankInSet").asInt()).isEqualTo(1);
        assertThat(results.get(1).get("rankInSet").asInt()).isEqualTo(2);   // UNAVAILABLE을 건너뛰고 1..m
        JsonNode unavailable = results.get(2);
        assertThat(unavailable.get("status").asText()).isEqualTo("UNAVAILABLE");
        SIX.forEach(f -> assertThat(unavailable.has(f)).as(f).isFalse());
        assertThat(unavailable.size()).isEqualTo(3);   // productKey, status, reason
        results.forEach(r -> {
            if (r.get("status").asText().equals("OK")) {
                SIX.forEach(f -> assertThat(r.has(f)).as(f).isTrue());
                assertThat(r.has("reason")).isFalse();
            }
        });
    }

    @Test
    void 전부_UNAVAILABLE이어도_발급된다() throws Exception {
        GradeScenario s = GradeScenario.standard(PolicyFixtures.GRADING_5, PolicyFixtures.RANKING_SHARED)
                .product("INS-A:PRD-1001", "0.8").product("INS-B:PRD-2044", "1.2").product("INS-C:PRD-3120", "1.0");
        DisclosureGradeService.Issued issued = s.service().issue(GradeScenario.request("INS-Q:TEMP-7", "INS-R:TEMP-8"));
        JsonNode results = SnapshotJson.mapper().readTree(issued.responseCanonical()).get("results");
        assertThat(results).hasSize(2);
        results.forEach(r -> assertThat(r.get("status").asText()).isEqualTo("UNAVAILABLE"));
        assertThat(s.snapshots.count()).isEqualTo(1);
    }
}
