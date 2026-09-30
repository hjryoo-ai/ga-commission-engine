package ga.comm.disclosure.grade;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ga.comm.disclosure.grade.fixture.PolicyFixtures;
import ga.comm.disclosure.grade.policy.GradingPolicySpec;
import ga.comm.disclosure.grade.policy.InvalidPolicyException;
import ga.comm.disclosure.grade.policy.PolicyLoader;
import ga.comm.disclosure.grade.policy.RankingPolicySpec;
import ga.comm.disclosure.grade.policy.SecondaryKey;
import ga.comm.disclosure.grade.policy.TieBreak;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E2: 구간 겹침·빈틈·ordinal 중복·라벨 누락(과 그 밖의 형식 결함) 정책은 로드 시 실패한다.
 * 정상 픽스처에서 한 곳씩만 망가뜨린 변형을 만들어, 각각이 기대한 문제 문구로 거부되는지 본다.
 */
class GradingPolicyValidationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ObjectNode five() {
        try {
            return (ObjectNode) JSON.readTree(PolicyFixtures.read(PolicyFixtures.GRADING_5));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ObjectNode grade(ObjectNode policy, int index) {
        return (ObjectNode) policy.get("grades").get(index);
    }

    /** grades[0]=VERY_HIGH(>1.30) [1]=HIGH(1.10,1.30] [2]=MID(0.90,1.10] [3]=LOW(0.70,0.90] [4]=VERY_LOW(≤0.70) */
    static Stream<Arguments> brokenPolicies() {
        return Stream.of(
                broken("겹침: 경계값이 두 등급에 속함(둘 다 포함)", "overlap at 1.10", p -> grade(p, 1).put("minInclusive", true)),
                broken("겹침: 구간이 겹침", "overlap", p -> grade(p, 2).put("maxRatio", "1.20")),
                broken("빈틈: 경계 사이 빈 구간", "gap between MID", p -> grade(p, 2).put("maxRatio", "1.05")),
                broken("빈틈: 경계값이 어느 쪽에도 속하지 않음(둘 다 제외)", "gap at 0.90", p -> grade(p, 3).put("maxInclusive", false)),
                broken("빈틈: 하한 없는 구간이 없음", "exactly one band must have no minRatio",
                        p -> grade(p, 4).put("minRatio", "0.00").put("minInclusive", true)),
                broken("상한 없는 구간이 2개", "exactly one no maxRatio", p -> grade(p, 1).remove(java.util.List.of("maxRatio", "maxInclusive"))),
                broken("ordinal 중복", "ordinal: duplicate 3", p -> grade(p, 1).put("ordinal", 3)),
                broken("ordinal이 비율과 반대로 감", "ordinal must increase", p -> {
                    grade(p, 0).put("ordinal", 1);
                    grade(p, 4).put("ordinal", 5);
                }),
                broken("라벨 누락", "label: required non-blank string", p -> grade(p, 2).remove("label")),
                broken("라벨 공백", "label: required non-blank string", p -> grade(p, 2).put("label", " ")),
                broken("코드 중복", "code: duplicate HIGH", p -> grade(p, 2).put("code", "HIGH")),
                broken("포함 여부 누락(기본값 없음)", "maxInclusive: required boolean", p -> grade(p, 2).remove("maxInclusive")),
                broken("경계가 JSON 숫자", "must be a decimal string", p -> grade(p, 2).put("maxRatio", new BigDecimal("1.10"))),
                broken("min ≥ max", "minRatio must be < maxRatio", p -> grade(p, 2).put("minRatio", "1.10")),
                broken("등급 없음", "grades: required non-empty array", p -> p.putArray("grades")),
                broken("알 수 없는 키", "colour: unknown key", p -> grade(p, 2).put("colour", "red")),
                broken("사유 매핑 누락", "unavailableReasons: missing NO_RATE_DATA",
                        p -> ((ObjectNode) p.get("unavailableReasons")).remove("NO_RATE_DATA")),
                broken("알 수 없는 원인", "unknown cause TYPO", p -> ((ObjectNode) p.get("unavailableReasons")).put("TYPO", "X")),
                // E3.1 §3-7: 임시등록은 엔진 원인이 아니다(요청에 오지 않음) — 정책 데이터에 넣으면 거부
                broken("임시등록 원인 없음", "unknown cause TEMP_PRODUCT",
                        p -> ((ObjectNode) p.get("unavailableReasons")).put("TEMP_PRODUCT", "TEMP_PRODUCT")),
                broken("반올림 UNNECESSARY", "UNNECESSARY is not a rounding mode", p -> p.put("ratioRounding", "UNNECESSARY")),
                broken("모집단 최소 0", "minPopulation: must be within", p -> ((ObjectNode) p.get("population")).put("minPopulation", 0)),
                broken("기간 종류 미지원", "kind: required", p -> ((ObjectNode) p.get("period")).put("kind", "CALENDAR_YEAR")));
    }

    @SafeVarargs
    private static Arguments broken(String name, String expected, Consumer<ObjectNode>... mutations) {
        ObjectNode policy = five();
        for (Consumer<ObjectNode> m : mutations) {
            m.accept(policy);
        }
        return Arguments.argumentSet(name, policy.toString(), expected);
    }

    @Test
    void 정상_픽스처는_로드된다() {
        GradingPolicySpec five = PolicyLoader.grading(PolicyFixtures.read(PolicyFixtures.GRADING_5));
        GradingPolicySpec four = PolicyLoader.grading(PolicyFixtures.read(PolicyFixtures.GRADING_4));
        assertThat(five.grades()).hasSize(5);
        assertThat(four.grades()).hasSize(4);
        // 경계값이 정확히 한 등급에 속한다(포함 여부가 데이터대로)
        assertThat(five.bandOf(new BigDecimal("1.30")).code()).isEqualTo("HIGH");
        assertThat(five.bandOf(new BigDecimal("1.31")).code()).isEqualTo("VERY_HIGH");
        assertThat(five.bandOf(new BigDecimal("0.70")).code()).isEqualTo("VERY_LOW");
        assertThat(five.bandOf(new BigDecimal("0.00")).code()).isEqualTo("VERY_LOW");
        assertThat(five.bandOf(new BigDecimal("0.71")).code()).isEqualTo("LOW");
    }

    @ParameterizedTest
    @MethodSource("brokenPolicies")
    void 결함_정책은_로드_시_실패한다(String body, String expectedProblem) {
        assertThatThrownBy(() -> PolicyLoader.grading(body))
                .isInstanceOf(InvalidPolicyException.class)
                .satisfies(e -> assertThat(((InvalidPolicyException) e).problems())
                        .anySatisfy(p -> assertThat(p).contains(expectedProblem)));
    }

    @Test
    void JSON이_아니거나_중복_키면_실패한다() {
        assertThatThrownBy(() -> PolicyLoader.grading("not json")).isInstanceOf(InvalidPolicyException.class);
        assertThatThrownBy(() -> PolicyLoader.grading("{\"ratioScale\":2,\"ratioScale\":3}"))
                .isInstanceOf(InvalidPolicyException.class).hasMessageContaining("Duplicate field");
    }

    @Test
    void 순위_정책_로드와_검증() {
        RankingPolicySpec shared = PolicyLoader.ranking(PolicyFixtures.read(PolicyFixtures.RANKING_SHARED));
        RankingPolicySpec strict = PolicyLoader.ranking(PolicyFixtures.read(PolicyFixtures.RANKING_STRICT));
        assertThat(shared.tieBreak()).isEqualTo(TieBreak.SHARED_RANK);
        assertThat(strict.secondaryKeys()).containsExactly(SecondaryKey.MEASURE_ASC, SecondaryKey.PRODUCT_KEY_ASC);

        assertThatThrownBy(() -> PolicyLoader.ranking("{\"tieBreak\":\"STRICT\",\"secondaryKeys\":[\"MEASURE_ASC\"]}"))
                .isInstanceOf(InvalidPolicyException.class).hasMessageContaining("total");
        assertThatThrownBy(() -> PolicyLoader.ranking("{\"tieBreak\":\"SHARED_RANK\",\"secondaryKeys\":[\"PRODUCT_KEY_ASC\"]}"))
                .isInstanceOf(InvalidPolicyException.class).hasMessageContaining("remove secondaryKeys");
        assertThatThrownBy(() -> PolicyLoader.ranking("{\"tieBreak\":\"RANDOM\"}")).isInstanceOf(InvalidPolicyException.class);
        assertThatThrownBy(() -> PolicyLoader.ranking("{\"tieBreak\":\"STRICT\",\"secondaryKeys\":[\"PRODUCT_KEY_ASC\",\"PRODUCT_KEY_ASC\"]}"))
                .isInstanceOf(InvalidPolicyException.class).hasMessageContaining("duplicate");
    }

    @Test
    void 배열_형태_변형은_ArrayNode로도_거부된다() {
        ObjectNode p = five();
        ArrayNode grades = (ArrayNode) p.get("grades");
        grades.remove(2);   // MID 제거 → 0.90~1.10 빈틈
        assertThatThrownBy(() -> PolicyLoader.grading(p.toString())).isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("gap between LOW");
    }
}
