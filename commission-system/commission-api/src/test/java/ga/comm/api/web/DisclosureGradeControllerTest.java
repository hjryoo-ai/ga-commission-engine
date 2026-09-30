package ga.comm.api.web;

import ga.comm.disclosure.grade.fixture.GradeScenario;
import ga.comm.disclosure.grade.fixture.PolicyFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E10: 테넌트 불일치 403, 중복 키·빈 배열·미래일 400(+ 형식·상품군), 정책 0건 422·모호 409, 없는 스냅샷 404.
 * 200 응답은 저장된 정규 바이트 그대로이고 GET이 바이트 동일하다(E7의 컨트롤러 층). 401(토큰)은 앱 보안 테스트가 본다.
 */
class DisclosureGradeControllerTest {

    private static final String URL = "/internal/v1/disclosure/commission-grades";

    private static MockMvc mvc(GradeScenario s) {
        return MockMvcBuilders.standaloneSetup(new DisclosureGradeController(s.service()))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new ByteArrayHttpMessageConverter(),
                        new MappingJackson2HttpMessageConverter(WebConfig.apiObjectMapper()))
                .build();
    }

    private static GradeScenario scenario() {
        return GradeScenario.standard(PolicyFixtures.GRADING_5, PolicyFixtures.RANKING_SHARED)
                .product("INS-A:PRD-1001", "0.84").product("INS-B:PRD-2044", "1.37").product("INS-C:PRD-3120", "1.02");
    }

    private static String body(String tenant, String asOf, String group, String productsJson) {
        return "{\"tenantId\":\"" + tenant + "\",\"asOfDate\":\"" + asOf + "\",\"productGroupCode\":\"" + group
                + "\",\"products\":" + productsJson + "}";
    }

    private static final String THREE = "[{\"productKey\":\"INS-A:PRD-1001\",\"insurerCode\":\"INS-A\"},"
            + "{\"productKey\":\"INS-B:PRD-2044\",\"insurerCode\":\"INS-B\"},{\"productKey\":\"INS-C:PRD-3120\",\"insurerCode\":\"INS-C\"}]";

    @Test
    void 발급_200과_재조회_바이트_동일() throws Exception {
        MockMvc mvc = mvc(scenario());
        MvcResult issued = mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body("T1", "2026-09-23", GradeScenario.GROUP, THREE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotId").value("GRD-20260923-1000000"))
                .andExpect(jsonPath("$.results[0].ratioToAvg").value("0.78"))
                .andReturn();
        byte[] first = issued.getResponse().getContentAsByteArray();
        byte[] again = mvc.perform(get(URL + "/{id}", "GRD-20260923-1000000")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(again).isEqualTo(first);
        assertThat(issued.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    }

    @Test
    void 모르는_필드는_무시하고_전부_산출불가도_200() throws Exception {
        mvc(scenario()).perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(
                        "{\"tenantId\":\"T1\",\"asOfDate\":\"2026-09-23\",\"productGroupCode\":\"" + GradeScenario.GROUP
                                + "\",\"extra\":1,\"products\":[{\"productKey\":\"INS-Z:TEMP-7\",\"insurerCode\":\"INS-Z\",\"x\":true}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.results[0].grade").doesNotExist())
                .andExpect(jsonPath("$.results[0].rankInSet").doesNotExist());
    }

    @Test
    void 테넌트_불일치_403() throws Exception {
        mvc(scenario()).perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body("T2", "2026-09-23", GradeScenario.GROUP, THREE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_MISMATCH"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 중복_키_400() throws Exception {
        mvc(scenario()).perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body("T1", "2026-09-23", GradeScenario.GROUP,
                        "[{\"productKey\":\"INS-A:PRD-1001\",\"insurerCode\":\"INS-A\"},{\"productKey\":\"INS-A:PRD-1001\",\"insurerCode\":\"INS-A\"}]")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 빈_배열_400() throws Exception {
        mvc(scenario()).perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body("T1", "2026-09-23", GradeScenario.GROUP, "[]")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 미래_기준일_400() throws Exception {
        mvc(scenario()).perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body("T1", "2026-09-24", GradeScenario.GROUP, THREE)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AS_OF_IN_FUTURE"));
    }

    @Test
    void 모집단_코드_체계에_없는_상품군_400() throws Exception {
        mvc(scenario()).perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body("T1", "2026-09-23", "PG-NOPE", THREE)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_PRODUCT_GROUP"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{not json",
            "{\"tenantId\":\"T1\",\"asOfDate\":\"2026-13-40\",\"productGroupCode\":\"G\",\"products\":[{\"productKey\":\"A:B\",\"insurerCode\":\"A\"}]}",
            "{\"tenantId\":\"t1\",\"asOfDate\":\"2026-09-23\",\"productGroupCode\":\"G\",\"products\":[{\"productKey\":\"A:B\",\"insurerCode\":\"A\"}]}",
            "{\"tenantId\":\"T1\",\"productGroupCode\":\"G\",\"products\":[{\"productKey\":\"A:B\",\"insurerCode\":\"A\"}]}",
            "{\"tenantId\":\"T1\",\"asOfDate\":\"2026-09-23\",\"products\":[{\"productKey\":\"A:B\",\"insurerCode\":\"A\"}]}",
            "{\"tenantId\":\"T1\",\"asOfDate\":\"2026-09-23\",\"productGroupCode\":\"G\"}",
            "{\"tenantId\":\"T1\",\"asOfDate\":\"2026-09-23\",\"productGroupCode\":\"G\",\"products\":[{\"productKey\":\"no-colon\",\"insurerCode\":\"A\"}]}",
            // 계약 1.2.0 상품 키 규칙(E3.1): 41자, 보험사 9자, 보험사 코드 형식 위반
            "{\"tenantId\":\"T1\",\"asOfDate\":\"2026-09-23\",\"productGroupCode\":\"G\",\"products\":[{\"productKey\":\"ABCDEFGH:P1111111111111111111111111111112\",\"insurerCode\":\"ABCDEFGH\"}]}",
            "{\"tenantId\":\"T1\",\"asOfDate\":\"2026-09-23\",\"productGroupCode\":\"G\",\"products\":[{\"productKey\":\"ABCDEFGHI:P1\",\"insurerCode\":\"ABCDEFGHI\"}]}",
            "{\"tenantId\":\"T1\",\"asOfDate\":\"2026-09-23\",\"productGroupCode\":\"G\",\"products\":[{\"productKey\":\"INS-A:P1\",\"insurerCode\":\"INS_A\"}]}",
    })
    void 형식_오류_400(String json) throws Exception {
        mvc(scenario()).perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 정책_0건_422_모호_409() throws Exception {
        GradeScenario none = new GradeScenario().ranking(PolicyFixtures.RANKING_SHARED).product("INS-A:PRD-1001", "1");
        mvc(none).perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body("T1", "2026-09-23", GradeScenario.GROUP,
                        "[{\"productKey\":\"INS-A:PRD-1001\",\"insurerCode\":\"INS-A\"}]")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NO_POLICY"));

        GradeScenario two = scenario();
        two.policies.addRanking("RANK-OTHER", LocalDate.of(2026, 9, 1), null, "ACTIVE", PolicyFixtures.read(PolicyFixtures.RANKING_STRICT));
        mvc(two).perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body("T1", "2026-09-23", GradeScenario.GROUP, THREE)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AMBIGUOUS_POLICY"));
    }

    @Test
    void 없는_스냅샷_404() throws Exception {
        mvc(scenario()).perform(get(URL + "/{id}", "GRD-20260923-1000009"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SNAPSHOT_NOT_FOUND"));
    }
}
