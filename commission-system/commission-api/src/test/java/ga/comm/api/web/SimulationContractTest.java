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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2 ②·C5 — 시뮬레이션 API 응답이 포털 계약 사본(v1.3)의 {@code NewContractResult}·{@code PolicyEventResult}·{@code Problem} 스키마를
 * 통과한다: 한도 초과(월납 150,000 + 정액 시책), 분급 커브 있음/없음, 2026-06-30 체결(한도 미적용 — 선택 필드 없음), 해약 6/7회차 경계
 * ({@code elapsedInstallments}), 그리고 계약의 오류 코드 전부. 금액의 정확성은 {@code DryRunSimulatorTest}(실제 처리와 대조).
 *
 * <p>포털 응답 샘플(포털 Phase 5 ⓪ — 포털 계획 Q17): {@link #포털_시뮬레이션_샘플()}이 포털이 실제로 보낼 요청과 그 응답 바이트를
 * {@code contracts/samples/simulations/}에 둔다. 기본 실행은 파일과 바이트를 대조하고(엔진이 바뀌면 이 시험이 먼저 실패),
 * {@code -Psamples.update=true}일 때만 파일을 다시 쓴다. 포털은 이 디렉터리를 사본으로 받아 스텁·E2E의 유일한 엔진 데이터로 쓴다.
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

    private static byte[] exchange(MockMvc m, String path, String body, int status) throws Exception {
        return m.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(status))
                .andReturn().getResponse().getContentAsByteArray();
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

    /** 포털 샘플 한 건 — {@code engineState}는 포털 스텁이 테넌트에 붙이는 엔진 상태 묶음(standard: 보험사 하나, ambiguous: 보험사 둘). */
    private record Sample(String name, String engineState, String path, String request, int status) {
        String kind() {
            return path.equals(NEW) ? "new-contract" : "policy-event";
        }
    }

    private static final String S1 = newContract("2026-08-01", "");
    private static final String CANCEL = "{\"agentId\":\"A-1001\",\"policyNo\":\"POL-2026-0001\",\"eventType\":\"CANCEL\",\"asOfDate\":\"2027-02-15\"";
    private static final String REDUCE = "{\"agentId\":\"A-1001\",\"policyNo\":\"POL-2026-0001\",\"eventType\":\"REDUCE\",\"asOfDate\":\"2027-02-15\"";

    /** 포털 계획 ③ 표 S1~S12 — 포털이 보낼 요청 그대로(보험사·시책 코드 없음 — 포털 Q3·Q4). */
    private static final List<Sample> SAMPLES = List.of(
            new Sample("new-limit-exceeded", "standard", NEW, S1, 200),
            new Sample("new-curve", "standard", NEW, newContract("2027-01-15", ""), 200),
            new Sample("new-no-limit", "standard", NEW, newContract("2026-06-30", ""), 200),
            new Sample("new-term-3", "standard", NEW, newContract("2026-08-01", ",\"paymentTermMonths\":3"), 200),
            new Sample("event-cancel-7", "standard", EVENT, CANCEL + ",\"elapsedInstallments\":7}", 200),
            new Sample("event-cancel-6", "standard", EVENT, CANCEL + ",\"elapsedInstallments\":6}", 200),
            new Sample("event-reduce", "standard", EVENT, REDUCE + ",\"elapsedInstallments\":7,\"newMonthlyPremium\":100000}", 200),
            new Sample("event-unsupported", "standard", EVENT,
                    "{\"agentId\":\"A-1001\",\"policyNo\":\"POL-2026-0001\",\"eventType\":\"PAYMENT_OVERDUE\",\"asOfDate\":\"2027-02-15\"}", 422),
            new Sample("event-unknown-policy", "standard", EVENT,
                    "{\"agentId\":\"A-1001\",\"policyNo\":\"POL-9999\",\"eventType\":\"CANCEL\",\"asOfDate\":\"2027-02-15\"}", 404),
            new Sample("new-unknown-agent", "standard", NEW, S1.replace("A-1001", "A-9999"), 404),
            new Sample("new-ambiguous", "ambiguous", NEW, S1, 422),
            new Sample("new-ambiguous-samlife", "ambiguous", NEW, newContract("2026-08-01", ",\"insurerCd\":\"SAMLIFE\""), 200),
            new Sample("event-reduce-no-premium", "standard", EVENT, REDUCE + ",\"elapsedInstallments\":7}", 400));

    @Test
    void 포털_시뮬레이션_샘플() throws Exception {
        Path dir = Path.of(System.getProperty("engine.simSamplesDir"));
        boolean update = Boolean.getBoolean("samples.update");
        StringBuilder index = new StringBuilder("[\n");
        for (Sample s : SAMPLES) {
            MockMvc m = s.engineState().equals("standard") ? mvc : ambiguousMvc;
            byte[] response = exchange(m, s.path(), s.request(), s.status());
            JsonNode json = JSON.readTree(response);
            String schema = s.status() != 200 ? "Problem" : s.path().equals(NEW) ? "NewContractResult" : "PolicyEventResult";
            assertThat(PortalContract.get().validateApiSchema(schema, json)).as(s.name() + " " + json).isEmpty();
            // 결정성: 같은 요청을 다시 보내면 같은 바이트다(샘플이 시계·ID 순서에 흔들리지 않는다)
            assertThat(exchange(m, s.path(), s.request(), s.status())).as(s.name()).isEqualTo(response);
            sample(dir, s.name() + ".request.json", s.request().getBytes(StandardCharsets.UTF_8), update);
            sample(dir, s.name() + ".response.json", response, update);
            index.append("  {\"name\":\"").append(s.name()).append("\",\"engineState\":\"").append(s.engineState())
                    .append("\",\"path\":\"").append(s.kind()).append("\",\"status\":").append(s.status()).append('}')
                    .append(s == SAMPLES.getLast() ? "\n" : ",\n");
        }
        sample(dir, "index.json", index.append("]\n").toString().getBytes(StandardCharsets.UTF_8), update);

        // 닫힌 집합 — 표에 없는 파일이 디렉터리에 남아 있으면 실패
        Set<String> expected = SAMPLES.stream().flatMap(s -> Stream.of(s.name() + ".request.json", s.name() + ".response.json"))
                .collect(Collectors.toCollection(TreeSet::new));
        expected.add("index.json");
        try (Stream<Path> files = Files.list(dir)) {
            Set<String> present = files.map(p -> p.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
            assertThat(present).isEqualTo(expected);
        }

        // 보험사·시책 코드 없이 보낸 S1이 기존 한도 초과 사례(코드 지정)와 같은 금액이다 — 코드가 없으면 시책 전부 적용
        JsonNode s1 = JSON.readTree(Files.readAllBytes(dir.resolve("new-limit-exceeded.response.json")));
        assertThat(s1.path("incentives").get(0).toString())
                .isEqualTo("{\"code\":\"Q4-NEW\",\"amount\":855000,\"limitCutAmt\":145000,\"limitIncluded\":true}");
        assertThat(s1.path("limitAmount").asLong()).isEqualTo(1_800_000);
        assertThat(JSON.readTree(Files.readAllBytes(dir.resolve("event-cancel-6.response.json"))).path("clawbackAmount").asLong())
                .isEqualTo(-1_260_000);
        assertThat(JSON.readTree(Files.readAllBytes(dir.resolve("new-ambiguous.response.json"))).path("code").asText())
                .isEqualTo("INSURER_AMBIGUOUS");
    }

    private static void sample(Path dir, String file, byte[] bytes, boolean update) throws IOException {
        Path p = dir.resolve(file);
        if (update) {
            Files.createDirectories(dir);
            Files.write(p, bytes);
        } else {
            assertThat(Files.exists(p)).as(p + " 없음 — ./gradlew :commission-api:test -Psamples.update=true 로 쓴다").isTrue();
            assertThat(Files.readAllBytes(p)).as(file + " 가 엔진 응답과 다르다 — 엔진이 바뀌었으면 -Psamples.update=true 로 다시 쓰고 포털에 알린다")
                    .isEqualTo(bytes);
        }
    }
}
