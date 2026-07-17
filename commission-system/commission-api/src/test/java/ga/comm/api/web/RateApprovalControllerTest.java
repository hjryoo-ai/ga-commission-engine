package ga.comm.api.web;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Rate;
import ga.comm.domain.type.Direction;
import ga.comm.rule.admin.RateApprovalService;
import ga.comm.rule.fixture.InMemoryRuleStore;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.rule.model.EffectivePeriod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 요율 승인 컨트롤러 슬라이스 (설계서 §10 Phase 12·16, §6.6) — 실승인자 = 인증 주체(principal) + 상태 위반 매핑.
 */
class RateApprovalControllerTest {

    private RateApprovalService approval;
    private long rateId;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        InMemoryRuleStore store = new InMemoryRuleStore();
        approval = new RateApprovalService(store);
        rateId = approval.registerDraft(Direction.OUTBOUND, RuleFixtures.INSURER, RuleFixtures.PRODUCT,
                CommTypeCode.RENEWAL, null, Rate.of("5.0"),
                EffectivePeriod.from(LocalDate.of(2026, 8, 1))).rateId();
        mvc = MockMvcBuilders.standaloneSetup(new RateApprovalController(approval))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(WebConfig.apiObjectMapper()))
                .build();
    }

    /** 인증 주체를 실승인자로 넘긴다(Security가 principal을 채우는 것을 슬라이스에서 모사). */
    private org.springframework.test.web.servlet.ResultActions approveAs(String principalName) throws Exception {
        Principal principal = () -> principalName;
        return mvc.perform(post("/api/rates/{rateId}/approve", rateId).principal(principal));
    }

    @Test
    void 인증_주체로_승인하면_ACTIVE로_전이하고_날짜는_ISO로_직렬화된다() throws Exception {
        approveAs("김승인")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.applyFrom").value("2026-08-01"));
    }

    @Test
    void 인증_주체가_없으면_409_인증없는_승인_금지() throws Exception {
        // Security가 401로 막지만, 컨트롤러도 방어적으로 빈 principal을 거부한다(→409)
        approveAs("")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void 승인_주체가_system이면_400_실명_요건() throws Exception {
        approveAs("system")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 이미_승인된_요율_재승인은_409_상태_위반() throws Exception {
        approveAs("김승인").andExpect(status().isOk());

        approveAs("이승인")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }
}
