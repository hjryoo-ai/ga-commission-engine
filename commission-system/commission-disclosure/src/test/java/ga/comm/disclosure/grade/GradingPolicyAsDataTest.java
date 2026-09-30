package ga.comm.disclosure.grade;

import com.fasterxml.jackson.databind.JsonNode;
import ga.comm.disclosure.grade.fixture.GradeScenario;
import ga.comm.disclosure.grade.fixture.PolicyFixtures;
import ga.comm.disclosure.grade.snapshot.SnapshotJson;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E1: 등급 정책을 5단계 → 4단계(임계치 변경)로 <b>데이터만</b> 바꾸면 같은 입력의 등급·ordinal이 바뀐다.
 * 두 실행은 같은 빌드의 같은 클래스({@link DisclosureGradeService} 등)를 쓰고, 다른 것은 정책 저장소에 넣은 픽스처 파일
 * ({@code grading-5-step.json} vs {@code grading-4-step.json})뿐이다. 프로덕션 소스에 임계치·라벨·코드가 없다는 것은
 * {@code DisclosureSourceRulesTest}가 소스 스캔으로 따로 단언한다.
 */
class GradingPolicyAsDataTest {

    /** 같은 상품군·요율·요청. 평균 = 1.000000 → ratio = 요율 그대로. */
    private static GradeScenario scenario(String gradingFixture) {
        return GradeScenario.standard(gradingFixture, PolicyFixtures.RANKING_SHARED)
                .product("INS-A:PRD-1001", "0.650000")
                .product("INS-B:PRD-2044", "0.850000")
                .product("INS-C:PRD-3120", "1.050000")
                .product("INS-D:PRD-4001", "1.150000")
                .product("INS-E:PRD-5001", "1.300000");
    }

    private static Map<String, String> gradesOf(String gradingFixture) throws Exception {
        String body = scenario(gradingFixture).service().issue(GradeScenario.request(
                "INS-A:PRD-1001", "INS-B:PRD-2044", "INS-C:PRD-3120", "INS-D:PRD-4001", "INS-E:PRD-5001")).responseCanonical();
        Map<String, String> grades = new LinkedHashMap<>();
        for (JsonNode r : SnapshotJson.mapper().readTree(body).get("results")) {
            grades.put(r.get("productKey").asText(), r.get("ratioToAvg").asText() + " " + r.get("grade").asText() + "/"
                    + r.get("gradeOrdinal").asInt() + " rank " + r.get("rankInSet").asInt());
        }
        return grades;
    }

    @Test
    void 임계치_데이터만_바꿔도_등급과_ordinal이_바뀐다() throws Exception {
        Map<String, String> five = gradesOf(PolicyFixtures.GRADING_5);
        Map<String, String> four = gradesOf(PolicyFixtures.GRADING_4);

        assertThat(five).containsExactly(
                Map.entry("INS-A:PRD-1001", "0.65 VERY_LOW/1 rank 1"),
                Map.entry("INS-B:PRD-2044", "0.85 LOW/2 rank 2"),
                Map.entry("INS-C:PRD-3120", "1.05 MID/3 rank 3"),
                Map.entry("INS-D:PRD-4001", "1.15 HIGH/4 rank 4"),
                Map.entry("INS-E:PRD-5001", "1.30 HIGH/4 rank 5"));
        assertThat(four).containsExactly(
                Map.entry("INS-A:PRD-1001", "0.65 VERY_LOW/1 rank 1"),
                Map.entry("INS-B:PRD-2044", "0.85 LOW/2 rank 2"),
                Map.entry("INS-C:PRD-3120", "1.05 MID/3 rank 3"),
                Map.entry("INS-D:PRD-4001", "1.15 MID/3 rank 4"),
                Map.entry("INS-E:PRD-5001", "1.30 HIGH/4 rank 5"));
        // 비율·순위는 정책과 무관(같은 측정값), 등급만 바뀐다
        assertThat(five).isNotEqualTo(four);
    }

    @Test
    void 정책_버전_ID도_데이터에서_온다() throws Exception {
        String body = scenario(PolicyFixtures.GRADING_4).service()
                .issue(GradeScenario.request("INS-A:PRD-1001", "INS-B:PRD-2044", "INS-C:PRD-3120")).responseCanonical();
        assertThat(SnapshotJson.mapper().readTree(body).get("gradingPolicyVersionId").asText())
                .isEqualTo(GradeScenario.gradingId(PolicyFixtures.GRADING_4));
    }
}
