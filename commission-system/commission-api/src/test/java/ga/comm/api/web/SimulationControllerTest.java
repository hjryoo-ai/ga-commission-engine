package ga.comm.api.web;

import ga.comm.api.SimulationService;
import ga.comm.rule.fixture.RuleFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 시뮬레이션 컨트롤러 슬라이스 (설계서 §10 Phase 12) — 직렬화 + fail-fast 오류 매핑. */
class SimulationControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SimulationService service = new SimulationService(RuleFixtures.standardRules());
        mvc = MockMvcBuilders.standaloneSetup(new SimulationController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(WebConfig.apiObjectMapper()))
                .build();
    }

    @Test
    void 한도_내_시뮬레이션이_정수_금액으로_직렬화된다() throws Exception {
        String json = """
                {"insurerCd":"SAMLIFE","productKey":"WHOLE-LIFE-20Y","contractDate":"2026-08-01",
                 "monthlyPremium":300000,"gradeCd":"SR","plannedIncentive":1000000}
                """;

        String body = mvc.perform(post("/api/commissions/simulate")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstYearTotal").value(3_335_500))
                .andExpect(jsonPath("$.limitAmount").value(3_600_000))
                .andExpect(jsonPath("$.overLimitAmount").value(0))
                .andExpect(jsonPath("$.deferralApplies").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"limitAmount\":3600000").doesNotContain("3600000.0");
        // 소진율은 금액이 아니라 비율(scale 2) — 지수 없이 92.65
        assertThat(body).contains("\"utilizationPct\":92.65");
    }

    @Test
    void 요율_미등록_상품은_422_RULE_NOT_FOUND로_매핑된다() throws Exception {
        String json = """
                {"insurerCd":"SAMLIFE","productKey":"NO-SUCH-PRODUCT","contractDate":"2026-08-01",
                 "monthlyPremium":300000,"gradeCd":"SR"}
                """;

        mvc.perform(post("/api/commissions/simulate")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RULE_NOT_FOUND"));
    }
}
