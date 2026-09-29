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
        // 계약 1.1.0: 401·403·422(POST) 응답이 있다(E3 계획 Q3 — ga-disclosure PR #2)
        JsonNode doc = new YAMLMapper().readTree(Files.readString(CONTRACTS.resolve(FILE)));
        assertThat(doc.at("/info/version").asText()).isEqualTo("1.1.0");
        assertThat(doc.at("/paths/~1internal~1v1~1disclosure~1commission-grades/post/responses").has("422")).isTrue();
    }
}
