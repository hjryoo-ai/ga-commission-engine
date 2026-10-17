package ga.comm.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.InMemoryCommCalcStore;
import ga.comm.calc.fixture.InMemoryPolicyEventStore;
import ga.comm.clawback.fixture.InMemoryClawbackReceivableStore;
import ga.comm.deferral.fixture.InMemoryDeferralScheduleStore;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.Direction;
import ga.comm.domain.type.EventType;
import ga.comm.limit.fixture.InMemoryLimitLedgerStore;
import ga.comm.outbox.contract.PortalContract;
import ga.comm.outbox.fixture.InMemoryOutboxStore;
import ga.comm.outbox.fixture.OutboxEngine;
import ga.comm.rule.fixture.InMemoryIncentiveStore;
import ga.comm.rule.fixture.InMemoryRuleStore;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.rule.model.CommRateRule;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.IncentiveRule;
import ga.comm.rule.model.PayoutKind;
import ga.comm.rule.model.RateStatus;
import ga.comm.settlement.fixture.InMemoryAdjustmentStore;
import ga.comm.settlement.fixture.InMemoryAgentSettlementStore;
import ga.comm.settlement.fixture.InMemoryPayoutStatementStore;
import ga.comm.settlement.fixture.InMemorySettleCloseStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2 ②·C5 — 시뮬레이션 API 응답이 포털 계약 사본(v1.3)의 {@code NewContractResult}·{@code PolicyEventResult}·{@code Problem} 스키마를
 * 통과한다: 한도 초과(월납 150,000 + 정액 시책), 분급 커브 있음/없음, 2026-06-30 체결(한도 미적용 — 선택 필드 없음), 해약 6/7회차 경계
 * ({@code elapsedInstallments}), 그리고 계약의 오류 코드 전부. 금액의 정확성은 {@code DryRunSimulatorTest}(실제 처리와 대조).
 */
class SimulationContractTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-02-15T00:30:00Z"), ZoneId.of("Asia/Seoul"));
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String NEW = "/internal/v1/simulations/new-contract";
    private static final String EVENT = "/internal/v1/simulations/policy-event";

    private MockMvc mvc;
    private MockMvc ambiguousMvc;

    private static OutboxEngine engine(InMemoryRuleStore rules) {
        return new OutboxEngine(rules, CalcTestHarness.standardDirectory(),
                new OutboxEngine.Raw(new InMemoryPolicyEventStore(), new InMemoryCommCalcStore(), new InMemoryLimitLedgerStore(),
                        new InMemoryDeferralScheduleStore(), new InMemoryClawbackReceivableStore(), new InMemoryAdjustmentStore(),
                        new InMemorySettleCloseStore(), new InMemoryAgentSettlementStore(), new InMemoryPayoutStatementStore(),
                        new InMemoryOutboxStore()),
                CLOCK, OutboxEngine.sequentialIds());
    }

    private static MockMvc mvc(OutboxEngine e, InMemoryIncentiveStore incentives) {
        return MockMvcBuilders.standaloneSetup(new SimulationInternalController(e.simulator(incentives, "A-1001"::equals)))
                .setMessageConverters(new ByteArrayHttpMessageConverter()).build();
    }

    @BeforeEach
    void setUp() {
        InMemoryIncentiveStore incentives = new InMemoryIncentiveStore();
        incentives.insert(new IncentiveRule(incentives.nextIncentiveId(), "Q4-NEW", RuleFixtures.INSURER, RuleFixtures.PRODUCT, null,
                "eventType == 'NEW'", PayoutKind.FIXED, Money.won(1_000_000), (Rate) null, EffectivePeriod.from(LocalDate.of(2026, 1, 1)),
                1L, RateStatus.ACTIVE));
        OutboxEngine e = engine(RuleFixtures.standardRules());
        // 해약 시뮬레이션의 대상 계약 — 2026-08-01 체결, 202608 마감
        e.calculator.process(new PolicyEvent(null, "SAMLIFE:POL-2026-0001:NEW", new PolicyNo("POL-2026-0001"), RuleFixtures.INSURER,
                RuleFixtures.PRODUCT, EventType.NEW, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), new AgentId("A-1001"),
                Money.won(150_000), null, null, Map.of("incentive_amount", "1000000")));
        e.closeService.close(CloseYm.of("202608"), "정산담당");
        mvc = mvc(e, incentives);

        InMemoryRuleStore two = RuleFixtures.standardRules();
        two.addActiveRate(new CommRateRule(900, Direction.OUTBOUND, new InsurerCode("KBLIFE"), RuleFixtures.PRODUCT, CommTypeCode.FY_COMM,
                null, Rate.of("6.0"), EffectivePeriod.from(LocalDate.of(2026, 1, 1)), 1, RateStatus.ACTIVE));
        ambiguousMvc = mvc(engine(two), new InMemoryIncentiveStore());
    }

    private static JsonNode ok(MockMvc m, String path, String body, String schema) throws Exception {
        byte[] out = m.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        JsonNode json = JSON.readTree(out);
        assertThat(PortalContract.get().validateApiSchema(schema, json)).as(json.toString()).isEmpty();
        return json;
    }

    private static String problem(MockMvc m, String path, String body, int status) throws Exception {
        byte[] out = m.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(status))
                .andReturn().getResponse().getContentAsByteArray();
        JsonNode json = JSON.readTree(out);
        assertThat(PortalContract.get().validateApiSchema("Problem", json)).as(json.toString()).isEmpty();
        return json.path("code").asText();
    }

    private static String newContract(String contractDate, String extra) {
        return "{\"agentId\":\"A-1001\",\"productKey\":\"WHOLE-LIFE-20Y\",\"monthlyPremium\":150000,\"contractDate\":\"" + contractDate + "\""
                + extra + "}";
    }

    @Test
    void 한도_초과_신계약() throws Exception {
        JsonNode r = ok(mvc, NEW, newContract("2026-08-01", ",\"insurerCd\":\"SAMLIFE\",\"incentiveCodes\":[\"Q4-NEW\"]"), "NewContractResult");
        assertThat(r.path("limitAmount").asLong()).isEqualTo(1_800_000);
        assertThat(r.path("projectedAccum").asLong()).isEqualTo(1_800_000);
        assertThat(r.path("incentives").get(0).toString())
                .isEqualTo("{\"code\":\"Q4-NEW\",\"amount\":855000,\"limitCutAmt\":145000,\"limitIncluded\":true}");
        assertThat(r.path("generatedAt").asText()).isEqualTo("2027-02-15T09:30:00+09:00");
    }

    @Test
    void 분급_커브_있음과_없음() throws Exception {
        JsonNode with = ok(mvc, NEW, newContract("2027-01-15", ""), "NewContractResult");
        assertThat(with.path("deferral").path("schedule").size()).isPositive();
        JsonNode without = ok(mvc, NEW, newContract("2026-08-01", ",\"paymentTermMonths\":3"), "NewContractResult");
        assertThat(without.path("deferral").path("schedule").size()).isZero();
        assertThat(without.path("fyCommissionByInstallment").size()).isEqualTo(3);
    }

    @Test
    void 한도_미적용_체결은_선택_필드가_없다() throws Exception {
        JsonNode r = ok(mvc, NEW, newContract("2026-06-30", ""), "NewContractResult");
        assertThat(r.has("limitAmount")).isFalse();
        assertThat(r.has("projectedAccum")).isFalse();
    }

    @Test
    void 해약_6_7회차_경계() throws Exception {
        String base = "{\"agentId\":\"A-1001\",\"policyNo\":\"POL-2026-0001\",\"eventType\":\"CANCEL\",\"asOfDate\":\"2027-02-15\"";
        JsonNode seven = ok(mvc, EVENT, base + ",\"elapsedInstallments\":7}", "PolicyEventResult");
        JsonNode six = ok(mvc, EVENT, base + ",\"elapsedInstallments\":6}", "PolicyEventResult");
        assertThat(seven.path("clawbackAmount").asLong()).isEqualTo(-720_000);
        assertThat(six.path("clawbackAmount").asLong()).isEqualTo(-1_260_000);
        assertThat(seven.path("offsetAgainstMonth").toString()).isEqualTo("{\"closeYm\":\"202702\",\"before\":0,\"after\":-720000}");
        assertThat(seven.path("ruleSnapshotId").asText()).isEqualTo(six.path("ruleSnapshotId").asText());
    }

    @Test
    void 계약의_오류_코드() throws Exception {
        assertThat(problem(mvc, NEW, "{\"agentId\":\"A-1001\"}", 400)).isEqualTo("INVALID_REQUEST");
        assertThat(problem(mvc, NEW, "not json", 400)).isEqualTo("INVALID_REQUEST");
        assertThat(problem(mvc, NEW, newContract("2026-02-30", ""), 400)).isEqualTo("INVALID_REQUEST");
        assertThat(problem(mvc, NEW, "{\"agentId\":\"A-9999\",\"productKey\":\"WHOLE-LIFE-20Y\",\"monthlyPremium\":1,\"contractDate\":\"2026-08-01\"}", 404))
                .isEqualTo("AGENT_NOT_FOUND");
        assertThat(problem(ambiguousMvc, NEW, newContract("2026-08-01", ""), 422)).isEqualTo("INSURER_AMBIGUOUS");

        String event = "{\"agentId\":\"A-1001\",\"policyNo\":\"%s\",\"eventType\":\"%s\",\"asOfDate\":\"2027-02-15\"}";
        assertThat(problem(mvc, EVENT, event.formatted("POL-9999", "CANCEL"), 404)).isEqualTo("POLICY_NOT_FOUND");
        assertThat(problem(mvc, EVENT, event.formatted("POL-2026-0001", "PAYMENT_OVERDUE"), 422)).isEqualTo("UNSUPPORTED_EVENT_TYPE");
        assertThat(problem(mvc, EVENT, event.formatted("POL-2026-0001", "REDUCE"), 400)).isEqualTo("INVALID_REQUEST");
        assertThat(problem(mvc, EVENT, event.formatted("POL-2026-0001", "EXPLODE"), 400)).isEqualTo("INVALID_REQUEST");
    }
}
