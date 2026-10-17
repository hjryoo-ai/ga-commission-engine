package ga.comm.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.calc.fixture.InMemoryCommCalcStore;
import ga.comm.calc.fixture.InMemoryPolicyEventStore;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.RecipientType;
import ga.comm.limit.fixture.InMemoryLimitLedgerStore;
import ga.comm.outbox.contract.PortalContract;
import ga.comm.outbox.feed.AgentMasterReader;
import ga.comm.outbox.feed.EventFeed;
import ga.comm.outbox.feed.StatementQuery;
import ga.comm.outbox.fixture.InMemoryAgentMasterReader;
import ga.comm.outbox.fixture.InMemoryOutboxStore;
import ga.comm.outbox.fixture.OutboxEngine;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.settlement.WithholdingTaxPolicy;
import ga.comm.settlement.fixture.InMemoryAdjustmentStore;
import ga.comm.settlement.fixture.InMemoryAgentSettlementStore;
import ga.comm.settlement.fixture.InMemoryPayoutStatementStore;
import ga.comm.settlement.fixture.InMemorySettleCloseStore;
import ga.comm.clawback.fixture.InMemoryClawbackReceivableStore;
import ga.comm.deferral.fixture.InMemoryDeferralScheduleStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2 ③·C6 — 명세 조회 API(MockMvc standalone). 200 본문은 포털 계약 사본의 payload 스키마로, 오류는 {@code Problem}으로 검증한다.
 * {@code CONFIRMED}는 같은 달 발행 이벤트(StatementConfirmed + 지급 뒤 PayoutCompleted의 세액·순액)와 필드 단위로 같다 — 마감 전 정정으로
 * REVERSED가 된 원본까지 있는 달에서(전 상태 합산 — 주입 S4가 겨눈다). 실 Oracle 대조는 {@code StatementQueryOracleIT}.
 */
class StatementQueryControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-05T09:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final EventFeed.Tx DIRECT = new EventFeed.Tx() {
        @Override
        public <T> T run(java.util.function.Supplier<T> work) {
            return work.get();
        }
    };
    private static final CloseYm AUG = CloseYm.of("202608");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final InMemoryOutboxStore outbox = new InMemoryOutboxStore();
    private OutboxEngine e;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        e = new OutboxEngine(RuleFixtures.standardRules(), CalcTestHarness.standardDirectory(),
                new OutboxEngine.Raw(new InMemoryPolicyEventStore(), new InMemoryCommCalcStore(), new InMemoryLimitLedgerStore(),
                        new InMemoryDeferralScheduleStore(), new InMemoryClawbackReceivableStore(), new InMemoryAdjustmentStore(),
                        new InMemorySettleCloseStore(), new InMemoryAgentSettlementStore(), new InMemoryPayoutStatementStore(), outbox),
                CLOCK, OutboxEngine.sequentialIds());
        AgentMasterReader agents = new InMemoryAgentMasterReader()
                .with(new AgentMasterReader.AgentMaster("A-1001", "허구설계사", LocalDate.of(2020, 1, 1), null));
        StatementQuery query = new StatementQuery(e.estimator, e.calcs, e.stores.adjustments(), e.raw.close(), e.raw.statements(), agents,
                outbox, DIRECT, WithholdingTaxPolicy.STANDARD_3_3, CLOCK);
        mvc = MockMvcBuilders.standaloneSetup(new StatementQueryController(query))
                .setMessageConverters(new ByteArrayHttpMessageConverter()).build();
        // 202608: 계약 둘 — 그중 하나를 마감 전에 정정(원본 REVERSED + 음수 reversal, 같은 달)
        e.calculator.process(EventFixtures.newContract());
        e.calculator.process(EventFixtures.newContract(new PolicyNo("POL-2026-0002"), LocalDate.of(2026, 8, 3), Money.won(200_000), Map.of()));
        long second = e.raw.calcs().findByCloseYm(AUG).stream()
                .filter(r -> r.recipientType() == RecipientType.AGENT && r.policyNo().value().equals("POL-2026-0002")).findFirst()
                .orElseThrow().calcId();
        e.revision.reverse(second, "입력 오류");
    }

    private JsonNode ok(String path, String type) throws Exception {
        byte[] body = mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        JsonNode json = JSON.readTree(body);
        assertThat(PortalContract.get().validatePayload(type, json)).as(json.toString()).isEmpty();
        return json;
    }

    private JsonNode problem(String path, int status) throws Exception {
        byte[] body = mvc.perform(get(path)).andExpect(status().is(status)).andReturn().getResponse().getContentAsByteArray();
        JsonNode json = JSON.readTree(body);
        assertThat(PortalContract.get().validateApiSchema("Problem", json)).as(json.toString()).isEmpty();
        return json;
    }

    private JsonNode lastEvent(String type) {
        List<JsonNode> all = e.eventsAfter(0);
        JsonNode found = null;
        for (JsonNode n : all) {
            if (n.path("type").asText().equals(type) && n.path("payload").path("closeYm").asText().equals("202608")) {
                found = n.path("payload");
            }
        }
        return found;
    }

    @Test
    void ESTIMATE는_산출기를_지금_실행한_값이고_발행하지_않는다() throws Exception {
        int before = outbox.all().size();
        JsonNode est = ok("/internal/v1/agents/A-1001/statements/202608?mode=ESTIMATE", "StatementEstimated");
        assertThat(outbox.all()).as("조회는 이벤트가 아니다").hasSize(before);
        var expected = e.estimator.estimate(AUG, "A-1001").orElseThrow();
        assertThat(est.path("net").asLong()).isEqualTo(expected.math().net().toLong());
        assertThat(est.path("basisSeq").asLong()).as("부여 없이 읽은 headSeq").isEqualTo(outbox.headSeq());
    }

    @Test
    void 마감_전_CONFIRMED는_404_STATEMENT_NOT_CONFIRMED() throws Exception {
        assertThat(problem("/internal/v1/agents/A-1001/statements/202608?mode=CONFIRMED", 404).path("code").asText())
                .isEqualTo("STATEMENT_NOT_CONFIRMED");
    }

    @Test
    void 마감월_ESTIMATE는_422_MONTH_CLOSED() throws Exception {
        e.closeService.close(AUG, "정산담당");
        assertThat(problem("/internal/v1/agents/A-1001/statements/202608?mode=ESTIMATE", 422).path("code").asText()).isEqualTo("MONTH_CLOSED");
    }

    @Test
    void CONFIRMED는_발행된_이벤트와_같다() throws Exception {
        e.closeService.close(AUG, "정산담당");
        JsonNode beforePayout = ok("/internal/v1/agents/A-1001/statements/202608?mode=CONFIRMED", "StatementConfirmed");
        assertThat(beforePayout).as("지급 전 = StatementConfirmed payload 그대로").isEqualTo(lastEvent("StatementConfirmed"));
        assertThat(beforePayout.has("withholdingTax")).isFalse();

        e.payoutService.run(AUG, 1);
        JsonNode afterPayout = ok("/internal/v1/agents/A-1001/statements/202608?mode=CONFIRMED", "StatementConfirmed");
        JsonNode confirmed = lastEvent("StatementConfirmed");
        JsonNode paid = lastEvent("PayoutCompleted");
        for (String f : List.of("agentId", "closeYm", "gross", "clawbackOffset", "adjustment", "closedAt")) {
            assertThat(afterPayout.path(f)).as(f).isEqualTo(confirmed.path(f));
        }
        assertThat(afterPayout.path("withholdingTax")).isEqualTo(paid.path("withholdingTax"));
        assertThat(afterPayout.path("net")).isEqualTo(paid.path("net"));
    }

    @Test
    void 엔진에_없는_설계사는_404_AGENT_NOT_FOUND() throws Exception {
        assertThat(problem("/internal/v1/agents/Z-9/statements/202608?mode=ESTIMATE", 404).path("code").asText()).isEqualTo("AGENT_NOT_FOUND");
    }

    @Test
    void 형식_오류는_400_INVALID_REQUEST() throws Exception {
        for (String path : List.of("/internal/v1/agents/A-1001/statements/202613?mode=ESTIMATE",
                "/internal/v1/agents/A-1001/statements/202608", "/internal/v1/agents/A-1001/statements/202608?mode=FINAL",
                "/internal/v1/agents/-x/statements/202608?mode=ESTIMATE")) {
            assertThat(problem(path, 400).path("code").asText()).as(path).isEqualTo("INVALID_REQUEST");
        }
    }
}
