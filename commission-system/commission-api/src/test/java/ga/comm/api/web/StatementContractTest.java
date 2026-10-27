package ga.comm.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.calc.fixture.InMemoryCommCalcStore;
import ga.comm.calc.fixture.InMemoryPolicyEventStore;
import ga.comm.clawback.fixture.InMemoryClawbackReceivableStore;
import ga.comm.deferral.fixture.InMemoryDeferralScheduleStore;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.limit.fixture.InMemoryLimitLedgerStore;
import ga.comm.outbox.contract.PortalContract;
import ga.comm.outbox.feed.AgentMasterReader;
import ga.comm.outbox.feed.EventFeed;
import ga.comm.outbox.feed.StatementQuery;
import ga.comm.outbox.fixture.InMemoryAgentMasterReader;
import ga.comm.outbox.fixture.InMemoryOutboxStore;
import ga.comm.outbox.fixture.OutboxEngine;
import ga.comm.outbox.fixture.SteppingClock;
import ga.comm.outbox.scenario.A1Scenario;
import ga.comm.outbox.scenario.A1ScenarioRunner;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.settlement.WithholdingTaxPolicy;
import ga.comm.settlement.fixture.InMemoryAdjustmentStore;
import ga.comm.settlement.fixture.InMemoryAgentSettlementStore;
import ga.comm.settlement.fixture.InMemoryPayoutStatementStore;
import ga.comm.settlement.fixture.InMemorySettleCloseStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 포털 명세 샘플(포털 Phase 7 ⓪ — 포털 계획 P-2·P-12·P-13): 포털 대사가 보낼 명세 조회와 그 응답 바이트를
 * {@code contracts/samples/statements/<엔진 상태>/}에 둔다. {@link SimulationContractTest}와 같은 방식 — 기본 실행은 파일과 대조하고
 * (200은 바이트, {@code Problem}은 값 — 컨트롤러가 {@code Map.of}로 만든다), {@code -Psamples.update=true}일 때만 다시 쓴다.
 *
 * <p>엔진 상태 묶음 셋. 같은 요청({@code A-1001}·{@code 202608}·{@code CONFIRMED})에 엔진 상태마다 다른 답이 있어 묶음으로 나눈다:
 * <ul>
 *   <li>{@code a1} — A-1 첫 판 뒤, 둘째 판의 잠정 명세 시각(잠정 명세 발행 직전). {@code 202702 ESTIMATE}의 {@code basisSeq}는 그때의
 *       머리 = 10이고, 본문은 둘째 판 seq 11({@code StatementEstimated})의 payload와 같다.</li>
 *   <li>{@code estimate-cycle-01} — E2 {@code EstimateEqualsActualGoldenTest} 케이스 01과 같은 입력. ESTIMATE는 잠정 명세 발행 직전,
 *       CONFIRMED는 지급 뒤.</li>
 *   <li>{@code estimate-cycle-05} — 같은 시험 케이스 05(분급 도래). ESTIMATE 하나 — 미리보기 몫이 든 엔진 응답(포털 계획 Q1 ⓑ′의 시험 데이터).</li>
 * </ul>
 * 조회 전에 대기 이벤트에 순번을 준다 — 포털은 피드로 순번이 붙은 이벤트만 보므로, 조회 시점의 머리는 포털이 받을 수 있는 마지막 순번이다.
 * 포털 사본은 스텁·시험·E2E의 유일한 엔진 명세 데이터다.
 */
class StatementContractTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final EventFeed.Tx DIRECT = new EventFeed.Tx() {
        @Override
        public <T> T run(java.util.function.Supplier<T> work) {
            return work.get();
        }
    };
    /** 엔진에 없는 설계사(포털 계획 P-12 — 허구 ID). */
    private static final String UNKNOWN = "A-9999-NONE";

    /** 샘플 한 건 — 요청은 경로 변수 둘과 {@code mode}. */
    private record Sample(String name, String agentId, String ym, String mode, int status) {
        String path() {
            return "/internal/v1/agents/" + agentId + "/statements/" + ym + "?mode=" + mode;
        }

        String request() {
            return "{\"agentId\":\"" + agentId + "\",\"ym\":\"" + ym + "\",\"mode\":\"" + mode + "\"}";
        }
    }

    /** 한 엔진 상태: 인메모리 엔진 + 같은 저장소를 읽는 명세 조회 API. */
    private static final class Engine {
        final SteppingClock clock = new SteppingClock(OffsetDateTime.parse("2026-01-01T00:00:00+09:00").toInstant(), SEOUL);
        final InMemoryOutboxStore outbox = new InMemoryOutboxStore();
        final OutboxEngine e = new OutboxEngine(RuleFixtures.standardRules(), CalcTestHarness.standardDirectory(),
                new OutboxEngine.Raw(new InMemoryPolicyEventStore(), new InMemoryCommCalcStore(), new InMemoryLimitLedgerStore(),
                        new InMemoryDeferralScheduleStore(), new InMemoryClawbackReceivableStore(), new InMemoryAdjustmentStore(),
                        new InMemorySettleCloseStore(), new InMemoryAgentSettlementStore(), new InMemoryPayoutStatementStore(), outbox),
                clock, OutboxEngine.sequentialIds());
        final MockMvc mvc;
        final Map<Sample, byte[]> taken = new LinkedHashMap<>();

        Engine() {
            AgentMasterReader agents = new InMemoryAgentMasterReader()
                    .with(new AgentMasterReader.AgentMaster("A-1001", "허구설계사", LocalDate.of(2020, 1, 1), null));
            StatementQuery query = new StatementQuery(e.estimator, e.calcs, e.stores.adjustments(), e.raw.close(), e.raw.statements(), agents,
                    outbox, DIRECT, WithholdingTaxPolicy.STANDARD_3_3, clock);
            mvc = MockMvcBuilders.standaloneSetup(new StatementQueryController(query))
                    .setMessageConverters(new ByteArrayHttpMessageConverter()).build();
        }

        void at(String local) {
            clock.set(OffsetDateTime.parse(local + ":00+09:00"));
        }

        /** 지금 상태에서 샘플을 받는다 — 스키마 통과, 같은 요청을 다시 보내도 같은 바이트(결정성). */
        JsonNode take(Sample s) throws Exception {
            outbox.assignPending(10_000);
            byte[] body = exchange(s);
            assertThat(exchange(s)).as(s.name()).isEqualTo(body);
            JsonNode json = JSON.readTree(body);
            String schema = s.status() != 200 ? null : s.mode().equals("ESTIMATE") ? "StatementEstimated" : "StatementConfirmed";
            assertThat(schema == null ? PortalContract.get().validateApiSchema("Problem", json)
                    : PortalContract.get().validatePayload(schema, json)).as(s.name() + " " + json).isEmpty();
            taken.put(s, body);
            return json;
        }

        private byte[] exchange(Sample s) throws Exception {
            return mvc.perform(get(s.path())).andExpect(status().is(s.status())).andReturn().getResponse().getContentAsByteArray();
        }

        /** 마지막으로 발행된 {@code type} 이벤트의 payload(그 달·A-1001)와 그 순번. */
        JsonNode lastEvent(String type, String ym) {
            JsonNode found = null;
            for (JsonNode n : e.eventsAfter(0)) {
                if (n.path("type").asText().equals(type) && n.path("payload").path("closeYm").asText().equals(ym)
                        && n.path("payload").path("agentId").asText().equals("A-1001")) {
                    found = n;
                }
            }
            assertThat(found).as(type + " " + ym).isNotNull();
            return found;
        }
    }

    private static final Sample A1_CONFIRMED = new Sample("202608-confirmed", "A-1001", "202608", "CONFIRMED", 200);
    private static final Sample A1_ESTIMATE = new Sample("202702-estimate", "A-1001", "202702", "ESTIMATE", 200);
    private static final Sample A1_NOT_CONFIRMED = new Sample("202702-confirmed-not-confirmed", "A-1001", "202702", "CONFIRMED", 404);
    private static final Sample A1_MONTH_CLOSED = new Sample("202608-estimate-month-closed", "A-1001", "202608", "ESTIMATE", 422);
    private static final Sample A1_UNKNOWN = new Sample("unknown-agent", UNKNOWN, "202608", "CONFIRMED", 404);
    private static final Sample C1_ESTIMATE = new Sample("202608-estimate", "A-1001", "202608", "ESTIMATE", 200);
    private static final Sample C1_CONFIRMED = new Sample("202608-confirmed", "A-1001", "202608", "CONFIRMED", 200);
    private static final Sample C5_ESTIMATE = new Sample("202801-estimate", "A-1001", "202801", "ESTIMATE", 200);

    /** A-1: 첫 판 → 둘째 판의 잠정 명세 시각에 다섯 조회 → 잠정 명세 발행(둘째 판 seq 11). */
    private static Engine a1() throws Exception {
        Engine g = new Engine();
        A1Scenario s = A1Scenario.portal150000();
        A1ScenarioRunner.firstEdition(s, A1ScenarioRunner.of(g.e), g.clock);
        g.clock.set(s.times().estimate());
        JsonNode estimate = g.take(A1_ESTIMATE);
        JsonNode confirmed = g.take(A1_CONFIRMED);
        g.take(A1_NOT_CONFIRMED);
        g.take(A1_MONTH_CLOSED);
        g.take(A1_UNKNOWN);
        g.e.estimateAndPublish(s.estimateYm());

        // 포털 계획 P-13: basisSeq 10 = 둘째 판 seq 11의 basisSeq, 본문 전체가 그 이벤트의 payload와 같다
        JsonNode published = g.lastEvent("StatementEstimated", "202702");
        assertThat(published.path("seq").asLong()).isEqualTo(11);
        assertThat(estimate.path("basisSeq").asLong()).isEqualTo(10);
        assertThat(estimate).isEqualTo(published.path("payload"));
        // 확정 명세 = 같은 달 StatementConfirmed + 지급 런의 세액·순액(계약 v1.3 R8)
        JsonNode stmt = g.lastEvent("StatementConfirmed", "202608").path("payload");
        JsonNode paid = g.lastEvent("PayoutCompleted", "202608").path("payload");
        assertThat(confirmed.path("gross")).isEqualTo(stmt.path("gross"));
        assertThat(confirmed.path("withholdingTax")).isEqualTo(paid.path("withholdingTax"));
        assertThat(confirmed.path("net")).isEqualTo(paid.path("net"));
        return g;
    }

    /** E2 잠정 명세 사이클 01(기본): 202608 신계약 → 다음 달 1일 02:10 ESTIMATE·발행 → 5일 마감 → 10일 지급 → CONFIRMED. */
    private static Engine cycle01() throws Exception {
        Engine g = new Engine();
        g.at("2026-08-01T09:00");
        g.e.calculator.process(EventFixtures.newContract());
        g.at("2026-09-01T02:10");
        JsonNode estimate = g.take(C1_ESTIMATE);
        g.e.estimateAndPublish(CloseYm.of("202608"));
        assertThat(estimate).isEqualTo(g.lastEvent("StatementEstimated", "202608").path("payload"));
        g.at("2026-09-05T18:00");
        g.e.closeService.close(CloseYm.of("202608"), "정산담당");
        g.at("2026-09-10T10:00");
        g.e.payoutService.run(CloseYm.of("202608"), 1);
        JsonNode confirmed = g.take(C1_CONFIRMED);
        // 예상 = 실제(E2 C2) — 새 입력 없이 마감·지급했다
        for (String f : List.of("gross", "clawbackOffset", "adjustment", "withholdingTax", "net")) {
            assertThat(confirmed.path(f)).as(f).isEqualTo(estimate.path(f));
        }
        return g;
    }

    /** E2 잠정 명세 사이클 05(분급 도래): 2027-01-15 신계약 → 2028-02-01 02:10 ESTIMATE(발행 직전). */
    private static Engine cycle05() throws Exception {
        Engine g = new Engine();
        g.at("2027-01-15T09:00");
        g.e.calculator.process(EventFixtures.newContract(new PolicyNo("POL-2027-0001"), LocalDate.of(2027, 1, 15), Money.won(300_000), Map.of()));
        g.at("2028-02-01T02:10");
        JsonNode estimate = g.take(C5_ESTIMATE);
        g.e.estimateAndPublish(CloseYm.of("202801"));
        assertThat(estimate).isEqualTo(g.lastEvent("StatementEstimated", "202801").path("payload"));
        // 분급 도래 몫(미리보기)이 gross에 있다 — 포털 계획 Q1 측정값
        assertThat(estimate.path("gross").asLong()).isEqualTo(378_000);
        return g;
    }

    @Test
    void 포털_명세_샘플() throws Exception {
        Path root = Path.of(System.getProperty("engine.statementSamplesDir"));
        boolean update = Boolean.getBoolean("samples.update");
        Map<String, Engine> states = new LinkedHashMap<>();
        states.put("a1", a1());
        states.put("estimate-cycle-01", cycle01());
        states.put("estimate-cycle-05", cycle05());

        for (Map.Entry<String, Engine> state : states.entrySet()) {
            Path dir = root.resolve(state.getKey());
            List<Map.Entry<Sample, byte[]>> taken = new ArrayList<>(state.getValue().taken.entrySet());
            StringBuilder index = new StringBuilder("[\n");
            Set<String> expected = new TreeSet<>();
            for (Map.Entry<Sample, byte[]> t : taken) {
                Sample s = t.getKey();
                sample(dir, s.name() + ".request.json", s.request().getBytes(StandardCharsets.UTF_8), update, true);
                sample(dir, s.name() + ".response.json", t.getValue(), update, s.status() == 200);
                expected.add(s.name() + ".request.json");
                expected.add(s.name() + ".response.json");
                index.append("  {\"name\":\"").append(s.name()).append("\",\"agentId\":\"").append(s.agentId()).append("\",\"ym\":\"")
                        .append(s.ym()).append("\",\"mode\":\"").append(s.mode()).append("\",\"status\":").append(s.status()).append('}')
                        .append(t == taken.getLast() ? "\n" : ",\n");
            }
            sample(dir, "index.json", index.append("]\n").toString().getBytes(StandardCharsets.UTF_8), update, true);
            expected.add("index.json");
            assertThat(list(dir)).as("닫힌 집합 " + dir).isEqualTo(expected);
        }
        assertThat(list(root)).as("엔진 상태 묶음은 셋").isEqualTo(new TreeSet<>(states.keySet()));
    }

    private static Set<String> list(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /** {@code exact}면 바이트, 아니면 JSON 값(키 순서 무시)으로 대조한다. */
    private static void sample(Path dir, String file, byte[] bytes, boolean update, boolean exact) throws IOException {
        Path p = dir.resolve(file);
        if (update) {
            Files.createDirectories(dir);
            Files.write(p, bytes);
        } else {
            assertThat(Files.exists(p)).as(p + " 없음 — ./gradlew :commission-api:test -Psamples.update=true 로 쓴다").isTrue();
            String hint = file + " 가 엔진 응답과 다르다 — 엔진이 바뀌었으면 -Psamples.update=true 로 다시 쓰고 포털에 알린다";
            if (exact) {
                assertThat(Files.readAllBytes(p)).as(hint).isEqualTo(bytes);
            } else {
                assertThat(JSON.readTree(Files.readAllBytes(p))).as(hint).isEqualTo(JSON.readTree(bytes));
            }
        }
    }
}
