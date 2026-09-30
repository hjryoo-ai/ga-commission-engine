package ga.comm.disclosure.grade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import ga.comm.disclosure.grade.fixture.GradeScenario;
import ga.comm.disclosure.grade.fixture.PolicyFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E11(provider 계약): 엔진이 만든 실제 응답이 ga-disclosure 계약({@code contracts/api/v1/engine-disclosure.openapi.yaml})의
 * 스키마를 통과한다 — oneOf 분기(OK/UNAVAILABLE)·{@code additionalProperties:false}·tieBreak 두 값·전부 산출불가.
 * 망가뜨린 응답(UNAVAILABLE에 등급 필드, ratioToAvg 숫자, OK에 tie 누락)은 스키마가 거부한다(검증이 실제로 작동한다는 대조군).
 * 계약 파일은 CHECKSUMS와 일치하고 UPSTREAM은 출처 커밋을 고정한다. OpenAPI(YAML)는 JSON으로 바꿔 $ref 포인터로 검증한다(원격 조회 없음).
 */
class EngineDisclosureContractTest {

    private static final Path CONTRACTS = Path.of(System.getProperty("engine.contractsDir"));
    private static final String FILE = "api/v1/engine-disclosure.openapi.yaml";
    private static final String IRI = "https://contracts.ga-disclosure.local/" + FILE;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonSchemaFactory factory;

    @BeforeAll
    static void load() throws IOException {
        String json = JSON.writeValueAsString(new YAMLMapper().readTree(Files.readString(CONTRACTS.resolve(FILE))));
        factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
                b -> b.schemaLoaders(loaders -> loaders.schemas(Map.of(IRI, json))));
    }

    private static JsonSchema schema(String name) {
        return factory.getSchema(SchemaLocation.of(IRI + "#/components/schemas/" + name));
    }

    private static Set<ValidationMessage> validateResponse(String body) throws IOException {
        return schema("CommissionGradesResponse").validate(JSON.readTree(body));
    }

    private static String issue(String ranking, String... keys) {
        return GradeScenario.standard(PolicyFixtures.GRADING_5, ranking)
                .product("INS-A:PRD-1001", "0.84").product("INS-B:PRD-2044", "1.37").product("INS-C:PRD-3120", "1.02")
                .product("INS-D:PRD-4001", "1.02")
                .service().issue(GradeScenario.request(keys)).responseCanonical();
    }

    @ParameterizedTest
    @ValueSource(strings = {PolicyFixtures.RANKING_SHARED, PolicyFixtures.RANKING_STRICT})
    void 실제_응답이_계약_스키마를_통과한다(String ranking) throws IOException {
        String body = issue(ranking, "INS-A:PRD-1001", "INS-B:PRD-2044", "INS-C:PRD-3120", "INS-D:PRD-4001", "INS-Z:TEMP-7");
        assertThat(validateResponse(body)).isEmpty();
        JsonNode tieBreak = JSON.readTree(body).get("tieBreak");
        assertThat(tieBreak.asText()).isEqualTo(ranking.equals(PolicyFixtures.RANKING_SHARED) ? "SHARED_RANK" : "STRICT");
    }

    @Test
    void 전부_산출불가_응답도_통과한다() throws IOException {
        assertThat(validateResponse(issue(PolicyFixtures.RANKING_SHARED, "INS-Y:T1", "INS-Z:T2"))).isEmpty();
    }

    @Test
    void 요청도_계약_스키마를_통과한다() throws IOException {
        GradeRequest r = GradeScenario.request("INS-A:PRD-1001", "INS-B:PRD-2044");
        ObjectNode node = JSON.createObjectNode().put("tenantId", r.tenantId()).put("asOfDate", r.asOfDate().toString())
                .put("productGroupCode", r.productGroupCode());
        ArrayNode products = node.putArray("products");
        r.products().forEach(p -> products.addObject().put("productKey", p.productKey()).put("insurerCode", p.insurerCode()));
        assertThat(schema("CommissionGradesRequest").validate(node)).isEmpty();
        node.putArray("products");
        assertThat(schema("CommissionGradesRequest").validate(node)).as("empty products").isNotEmpty();
    }

    @Test
    void 망가뜨린_응답은_거부된다_대조군() throws IOException {
        String body = issue(PolicyFixtures.RANKING_SHARED, "INS-A:PRD-1001", "INS-Z:TEMP-7");
        ObjectNode ok = (ObjectNode) JSON.readTree(body);
        ((ObjectNode) ok.get("results").get(1)).put("grade", "LOW");                 // UNAVAILABLE에 등급 필드
        assertThat(schema("CommissionGradesResponse").validate(ok)).isNotEmpty();

        ObjectNode numeric = (ObjectNode) JSON.readTree(body);
        ((ObjectNode) numeric.get("results").get(0)).put("ratioToAvg", new java.math.BigDecimal("0.78"));   // 숫자 직렬화
        assertThat(schema("CommissionGradesResponse").validate(numeric)).isNotEmpty();

        ObjectNode missingTie = (ObjectNode) JSON.readTree(body);
        ((ObjectNode) missingTie.get("results").get(0)).remove("tie");
        assertThat(schema("CommissionGradesResponse").validate(missingTie)).isNotEmpty();

        ObjectNode nullField = (ObjectNode) JSON.readTree(body);
        ((ObjectNode) nullField.get("results").get(1)).putNull("rankInSet");         // null 출력
        assertThat(schema("CommissionGradesResponse").validate(nullField)).isNotEmpty();

        ObjectNode badTie = (ObjectNode) JSON.readTree(body);
        badTie.put("tieBreak", "RANDOM");
        assertThat(schema("CommissionGradesResponse").validate(badTie)).isNotEmpty();
    }

    @Test
    void 계약_파일은_CHECKSUMS와_일치하고_UPSTREAM이_출처를_고정한다() throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(CONTRACTS.resolve(FILE))));
        List<String> lines = Files.readAllLines(CONTRACTS.resolve("CHECKSUMS"));
        assertThat(lines).contains(hash + "  " + FILE);
        assertThat(Files.readString(CONTRACTS.resolve("UPSTREAM")).trim())
                .matches(Pattern.compile("^hjryoo-ai/ga-disclosure@[0-9a-f]{40}$"));
        // 계약 1.1.0: 401·403·422(POST) 응답이 있다(E3 계획 Q3 — ga-disclosure PR #2). 1.2.0: E3.1(ga-disclosure PR #4)
        JsonNode doc = new YAMLMapper().readTree(Files.readString(CONTRACTS.resolve(FILE)));
        assertThat(doc.at("/info/version").asText()).isEqualTo("1.2.0");
        assertThat(doc.at("/paths/~1internal~1v1~1disclosure~1commission-grades/post/responses").has("422")).isTrue();
    }

    /**
     * E3.1 §3-3: 엔진 E3 요청 1~4가 계약에 있고 엔진 구현이 그 코드를 낸다 — POST 400 AS_OF_IN_FUTURE·UNKNOWN_PRODUCT_GROUP
     * (GradeRequestException), POST 422 INVALID_POLICY, GET 500 SNAPSHOT_INTEGRITY. GET 403은 "인가 거부(역할 없음)"이고
     * 다른 테넌트의 스냅샷은 404다(DisclosureGradeService.refetch가 테넌트 불일치를 SnapshotNotFound로 낸다).
     */
    @Test
    void 계약_1_2_0의_오류_코드와_GET_403_설명() throws Exception {
        JsonNode doc = new YAMLMapper().readTree(Files.readString(CONTRACTS.resolve(FILE)));
        JsonNode post = doc.at("/paths/~1internal~1v1~1disclosure~1commission-grades/post/responses");
        JsonNode get = doc.at("/paths/~1internal~1v1~1disclosure~1commission-grades~1{snapshotId}/get/responses");
        assertThat(post.at("/400/description").asText()).contains("AS_OF_IN_FUTURE", "UNKNOWN_PRODUCT_GROUP");
        assertThat(post.at("/422/description").asText()).contains("NO_POLICY", "POLICY_SELF_CHECK_FAILED", "INVALID_POLICY");
        assertThat(get.has("500")).isTrue();
        assertThat(get.at("/500/description").asText()).contains("SNAPSHOT_INTEGRITY");
        assertThat(get.at("/403/description").asText()).contains("인가 거부").contains("404");
        assertThat(doc.at("/components/schemas/GradeResultUnavailable/properties/reason/description").asText())
                .contains("TEMP_PRODUCT를 내지 않는다");
    }

    /** E3.1 §3-2: 엔진 요청 검증(400)과 계약 요청 스키마가 같은 키를 받고 같은 키를 거부한다 — 한쪽만 바뀌면 실패. */
    @ParameterizedTest
    @ValueSource(strings = {
            "INS-A:PRD-1001|INS-A", "ABCDEFGH:P111111111111111111111111111111|ABCDEFGH", "A:1|A", "INS-A:p.r_d-1|INS-A",
            "ABCDEFGH:P1111111111111111111111111111112|ABCDEFGH", "ABCDEFGHI:P1|ABCDEFGHI", "INS_A:P1|INS_A", "-INS:P1|-INS",
            "INS-A:.P1|INS-A", "INS-A:-P1|INS-A", "ins-a:P1|ins-a", "INS-A:P:1|INS-A", "INS-A:P1|INS_A", "INS-A:P1|ABCDEFGHI"})
    void 엔진_요청_검증과_계약_스키마의_키_판정이_같다(String spec) {
        String key = spec.substring(0, spec.lastIndexOf('|'));
        String insurer = spec.substring(spec.lastIndexOf('|') + 1);
        ObjectNode node = JSON.createObjectNode().put("tenantId", "T1").put("asOfDate", "2026-09-23").put("productGroupCode", "PG");
        node.putArray("products").addObject().put("productKey", key).put("insurerCode", insurer);
        boolean schemaAccepts = schema("CommissionGradesRequest").validate(node).isEmpty();
        boolean engineAccepts;
        try {
            GradeRequest.of("T1", java.time.LocalDate.of(2026, 9, 23), "PG", List.of(new GradeRequest.Product(key, insurer)));
            engineAccepts = true;
        } catch (GradeRequestException e) {
            engineAccepts = false;
        }
        assertThat(engineAccepts).as("engine vs contract for %s", spec).isEqualTo(schemaAccepts);
    }
}
