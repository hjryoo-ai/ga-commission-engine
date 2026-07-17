package ga.comm.api.web;

import ga.comm.api.CommissionQueryService;
import ga.comm.calc.fixture.InMemoryCommCalcStore;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.deferral.fixture.InMemoryDeferralScheduleStore;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;
import ga.comm.domain.type.RecipientType;
import ga.comm.limit.fixture.InMemoryLimitLedgerStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 조회 컨트롤러 슬라이스 (설계서 §10 Phase 12) — MockMvc standalone.
 *
 * <p>완료 기준: reversal 시나리오(마감 전 정정 3종 공존)에서 순액 API가 정확하다. 순액은
 * 파사드가 NetAmountCalculator를 경유해 산출하므로 상태 필터 이중 차감(−135,000)이 재현되지 않는다.
 */
class CommissionQueryControllerTest {

    private final InMemoryCommCalcStore calcStore = new InMemoryCommCalcStore();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        CommissionQueryService queryService = new CommissionQueryService(
                calcStore, new InMemoryLimitLedgerStore(), new InMemoryDeferralScheduleStore());
        mvc = MockMvcBuilders.standaloneSetup(new CommissionQueryController(queryService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(WebConfig.apiObjectMapper()))
                .build();
    }

    private void seed(long amount, CalcStatus status, Long reversalOf) {
        calcStore.insert(new CommCalcRecord(null, 1L, new PolicyNo("POL-REV"), RecipientType.AGENT,
                "A-1001", CommTypeCode.FY_COMM, Money.won(Math.abs(amount)), Rate.of("1"),
                Money.won(amount), Money.ZERO, CloseYm.of("202608"), status, reversalOf, "[]", "[]"));
    }

    @Test
    void reversal_시나리오에서_순액_API가_정확하다() throws Exception {
        // 마감 전 정정: 원본 REVERSED + 음수 reversal(CALCULATED) + rebook(CALCULATED) 공존
        CommCalcRecord original = calcStore.insert(new CommCalcRecord(null, 1L,
                new PolicyNo("POL-REV"), RecipientType.AGENT, "A-1001", CommTypeCode.FY_COMM,
                Money.won(1_890_000), Rate.of("1"), Money.won(1_890_000), Money.ZERO,
                CloseYm.of("202608"), CalcStatus.REVERSED, null, "[]", "[]"));
        seed(-1_890_000, CalcStatus.CALCULATED, original.calcId());
        seed(1_755_000, CalcStatus.CALCULATED, null);

        // 전 상태 합산 = 1,755,000 (상태 필터였다면 −135,000)
        mvc.perform(get("/api/commissions/policies/{p}/agents/{a}", "POL-REV", "A-1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netAmount").value(1_755_000))
                .andExpect(jsonPath("$.records.length()").value(3));
    }

    @Test
    void 금액은_정수_마감월은_yyyyMM_문자열로_직렬화된다() throws Exception {
        seed(1_755_000, CalcStatus.CONFIRMED, null);

        String body = mvc.perform(get("/api/commissions/policies/{p}/agents/{a}", "POL-REV", "A-1001"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 금액은 정수 리터럴 — 소수점·지수 표기가 새지 않는다 (부록 B-1의 연장)
        assertThat(body).contains("\"netAmount\":1755000");
        assertThat(body).contains("\"calcAmount\":1755000");
        assertThat(body).doesNotContain("1755000.0").doesNotContain("1.755");
        assertThat(body).contains("\"closeYm\":\"202608\"");
    }
}
