package ga.comm.api.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import ga.comm.api.web.SimulationInternalController;
import ga.comm.api.web.StatementQueryController;
import ga.comm.calc.fixture.InMemoryCommCalcStore;
import ga.comm.calc.fixture.InMemoryPolicyEventStore;
import ga.comm.clawback.fixture.InMemoryClawbackReceivableStore;
import ga.comm.deferral.fixture.InMemoryDeferralScheduleStore;
import ga.comm.limit.fixture.InMemoryLimitLedgerStore;
import ga.comm.outbox.contract.PortalContract;
import ga.comm.outbox.feed.AgentMasterReader;
import ga.comm.outbox.feed.EventFeed;
import ga.comm.outbox.feed.StatementQuery;
import ga.comm.outbox.fixture.InMemoryAgentMasterReader;
import ga.comm.outbox.fixture.InMemoryOutboxStore;
import ga.comm.outbox.fixture.OutboxEngine;
import ga.comm.outbox.fixture.SteppingClock;
import ga.comm.rule.fixture.InMemoryIncentiveStore;
import ga.comm.settlement.WithholdingTaxPolicy;
import ga.comm.settlement.fixture.InMemoryAdjustmentStore;
import ga.comm.settlement.fixture.InMemoryAgentSettlementStore;
import ga.comm.settlement.fixture.InMemoryPayoutStatementStore;
import ga.comm.settlement.fixture.InMemorySettleCloseStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 포털 Phase 8 ⓪b — 데모 테넌트의 엔진 스트림과 그 설계사들의 명세·시뮬레이션 샘플({@code contracts/samples/demo/}). {@link DemoScenario}를
 * 인메모리 엔진(발행 데코레이터 + 전역 검증기 — 모든 이벤트가 포털 계약을 통과해야 적재된다)에 돌린다. 기본 실행은 파일과 바이트로 대조하고,
 * {@code -Psamples.update=true}일 때만 다시 쓴다(포털 사본 갱신을 알린다).
 *
 * <ul>
 *   <li>{@code demo_ga.jsonl} — 스트림(seq 1부터, Envelope 한 줄씩).</li>
 *   <li>{@code statements.json} — 명세 조회(포털 대사가 보낼 것): 스트림의 {@code StatementConfirmed}마다 CONFIRMED 하나, 열린 달 202802는
 *       설계사마다 ESTIMATE 하나(잠정 명세 발행 직전 — 그때 머리가 {@code basisSeq}). 한 파일로 묶는다(포털 승인 Q11).</li>
 *   <li>{@code simulations.json} — 설계사마다 신계약 하나·해약 하나(포털 화면이 보낼 요청 그대로).</li>
 *   <li>{@code cases.json} — 부록 B 필수 케이스 ↔ seq(포털 보고서의 케이스 표).</li>
 * </ul>
 */
class DemoContractTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final EventFeed.Tx DIRECT = new EventFeed.Tx() {
        @Override
        public <T> T run(java.util.function.Supplier<T> work) {
            return work.get();
        }
    };
    private static final String NEW = "/internal/v1/simulations/new-contract";
    private static final String EVENT = "/internal/v1/simulations/policy-event";
    private static final String SIM_DATE = "2028-02-15";

    @Test
    void 데모_스트림과_샘플() throws Exception {
        DemoScenario s = DemoScenario.standard();
        SteppingClock clock = new SteppingClock(DemoScenario.START.toInstant(), SEOUL);
        InMemoryOutboxStore outbox = new InMemoryOutboxStore();
        OutboxEngine e = new OutboxEngine(s.rules(), s.directory(),
                new OutboxEngine.Raw(new InMemoryPolicyEventStore(), new InMemoryCommCalcStore(), new InMemoryLimitLedgerStore(),
                        new InMemoryDeferralScheduleStore(), new InMemoryClawbackReceivableStore(), new InMemoryAdjustmentStore(),
                        new InMemorySettleCloseStore(), new InMemoryAgentSettlementStore(), new InMemoryPayoutStatementStore(), outbox),
                clock, OutboxEngine.sequentialIds(), s.policyStatus());
        InMemoryAgentMasterReader masters = new InMemoryAgentMasterReader();
        for (DemoScenario.Agent a : s.agents()) {
            masters.with(new AgentMasterReader.AgentMaster(a.agentId(), a.name(), DemoScenario.DIRECTORY_FROM, null));
        }
        MockMvc statements = MockMvcBuilders.standaloneSetup(new StatementQueryController(new StatementQuery(e.estimator, e.calcs,
                        e.stores.adjustments(), e.raw.close(), e.raw.statements(), masters, outbox, DIRECT, WithholdingTaxPolicy.STANDARD_3_3, clock)))
                .setMessageConverters(new ByteArrayHttpMessageConverter()).build();

        s.runUntilEstimate(e, clock);
        outbox.assignPending(1_000_000);
        StringBuilder statementLines = new StringBuilder();
        int estimates = 0;
        for (DemoScenario.Agent a : s.agents()) {
            byte[] body = statement(statements, a.agentId(), "202802", "ESTIMATE", 200);
            assertThat(PortalContract.get().validatePayload("StatementEstimated", JSON.readTree(body))).as(a.agentId()).isEmpty();
            statementLines.append(entry(a.agentId(), "202802", "ESTIMATE", body)).append(",\n");
            estimates++;
        }
        s.publishEstimate(e);

        List<JsonNode> events = all(e);
        assertThat(events.size()).as("재구축 상한 50,000 안").isBetween(10_000, 50_000);
        Set<String> confirmed = new LinkedHashSet<>();
        for (JsonNode n : events) {
            if (n.path("type").asText().equals("StatementConfirmed")) {
                confirmed.add(n.path("payload").path("agentId").asText() + "|" + n.path("payload").path("closeYm").asText());
            }
        }
        List<String> confirmedKeys = new ArrayList<>(confirmed);
        for (String key : confirmedKeys) {
            String[] k = key.split("\\|");
            byte[] body = statement(statements, k[0], k[1], "CONFIRMED", 200);
            assertThat(PortalContract.get().validatePayload("StatementConfirmed", JSON.readTree(body))).as(key).isEmpty();
            statementLines.append(entry(k[0], k[1], "CONFIRMED", body)).append(key.equals(confirmedKeys.getLast()) ? "\n" : ",\n");
        }
        assertThat(estimates).isEqualTo(30);

        // 시뮬레이션 — 끝 상태에서(설계사마다 신계약 하나·해약 하나)
        Set<String> agentIds = s.agents().stream().map(DemoScenario.Agent::agentId).collect(Collectors.toSet());
        MockMvc sims = MockMvcBuilders.standaloneSetup(new SimulationInternalController(e.simulator(new InMemoryIncentiveStore(), agentIds::contains)))
                .setMessageConverters(new ByteArrayHttpMessageConverter()).build();
        StringBuilder simLines = new StringBuilder();
        Map<String, List<DemoScenario.Contract>> byAgent = s.byAgent();
        List<DemoScenario.Agent> agents = s.agents();
        for (DemoScenario.Agent a : agents) {
            String nc = "{\"agentId\":\"" + a.agentId() + "\",\"productKey\":\"WHOLE-LIFE-20Y\",\"monthlyPremium\":100000,\"contractDate\":\"" + SIM_DATE + "\"}";
            simLines.append(sim(sims, a.agentId() + "-new-contract", "new-contract", NEW, nc, "NewContractResult")).append(",\n");
            DemoScenario.Contract target = byAgent.get(a.agentId()).stream()
                    .filter(c -> c.caseId() == null && c.cancelAt() == null && c.contractDate().isBefore(LocalDate.of(2027, 6, 1)))
                    .findFirst().orElseThrow();
            String ev = "{\"agentId\":\"" + a.agentId() + "\",\"policyNo\":\"" + target.policyNo()
                    + "\",\"eventType\":\"CANCEL\",\"asOfDate\":\"" + SIM_DATE + "\",\"elapsedInstallments\":7}";
            simLines.append(sim(sims, a.agentId() + "-cancel", "policy-event", EVENT, ev, "PolicyEventResult"))
                    .append(a == agents.getLast() ? "\n" : ",\n");
        }

        StringBuilder stream = new StringBuilder();
        for (JsonNode n : events) {
            stream.append(n.toString()).append('\n');
        }
        String cases = cases(s, events);

        Path root = Path.of(System.getProperty("engine.demoSamplesDir"));
        boolean update = Boolean.getBoolean("samples.update");
        file(root, DemoScenario.STREAM + ".jsonl", stream.toString(), update);
        file(root, "statements.json", "[\n" + statementLines + "]\n", update);
        file(root, "simulations.json", "[\n" + simLines + "]\n", update);
        file(root, "cases.json", cases, update);
        try (Stream<Path> files = Files.list(root)) {
            Set<String> names = files.map(p -> p.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
            assertThat(names).as("닫힌 집합").containsExactly("cases.json", DemoScenario.STREAM + ".jsonl", "simulations.json", "statements.json");
        }
    }

    /** 부록 B 필수 케이스를 스트림에서 찾아 seq로 적는다 — 하나라도 없으면 실패. */
    private static String cases(DemoScenario s, List<JsonNode> events) {
        Map<String, List<Long>> found = new LinkedHashMap<>();
        Map<String, String> ledgerOf = new LinkedHashMap<>();
        Map<String, Long> limitOf = new LinkedHashMap<>();
        for (JsonNode n : events) {
            if (type(n, "LimitLedgerOpened")) {
                ledgerOf.put(p(n, "policyNo"), p(n, "ledgerId"));
                limitOf.put(p(n, "ledgerId"), n.path("payload").path("limitAmount").asLong());
            }
        }
        // 한도 80·95 — 그 계약 원장의 마지막 전기 누계가 [80%, 95%)·[95%, 100%)
        for (String c : List.of(DemoScenario.CASE_LIMIT_80, DemoScenario.CASE_LIMIT_95)) {
            String ledger = ledgerOf.get(s.caseContract(c).policyNo());
            JsonNode last = lastOf(events, n -> type(n, "LimitLedgerPosted") && p(n, "ledgerId").equals(ledger));
            long accum = last.path("payload").path("accumAfter").asLong();
            long limit = limitOf.get(ledger);
            long low = c.equals(DemoScenario.CASE_LIMIT_80) ? 80 : 95;
            long high = c.equals(DemoScenario.CASE_LIMIT_80) ? 95 : 100;
            assertThat(accum * 100).as(c).isGreaterThanOrEqualTo(low * limit).isLessThan(high * limit);
            found.put(c, List.of(last.path("seq").asLong()));
        }
        // 100% — 삭감(CUT)·이연(DEFER_AFTER_FY)
        for (String c : List.of(DemoScenario.CASE_LIMIT_CUT, DemoScenario.CASE_LIMIT_DEFER)) {
            String policy = s.caseContract(c).policyNo();
            String action = c.equals(DemoScenario.CASE_LIMIT_CUT) ? "CUT" : "DEFER_AFTER_FY";
            JsonNode cut = firstOf(events, n -> type(n, "CommissionCalculated") && p(n, "policyNo").equals(policy)
                    && n.path("payload").path("limitCutAmt").asLong() > 0 && p(n, "limitCutPolicy").equals(action));
            found.put(c, List.of(cut.path("seq").asLong()));
        }
        // HELD — 그 계약 스케줄의 상태 변경 to = HELD
        Set<String> heldSchedules = events.stream().filter(n -> type(n, "DeferralScheduled") && p(n, "policyNo").equals(DemoScenario.HELD_POLICY))
                .map(n -> p(n, "scheduleId")).collect(Collectors.toSet());
        found.put(DemoScenario.CASE_HELD, List.of(firstOf(events, n -> type(n, "DeferralStatusChanged") && heldSchedules.contains(p(n, "scheduleId"))
                && p(n, "to").equals("HELD")).path("seq").asLong()));
        // 한도 경계 — 06-30은 원장 없음, 07-01은 원장
        assertThat(ledgerOf).as(DemoScenario.CASE_LIMIT_0630).doesNotContainKey(s.caseContract(DemoScenario.CASE_LIMIT_0630).policyNo());
        found.put(DemoScenario.CASE_LIMIT_0630, List.of(firstOf(events, n -> type(n, "CommissionCalculated")
                && p(n, "policyNo").equals(s.caseContract(DemoScenario.CASE_LIMIT_0630).policyNo())).path("seq").asLong()));
        found.put(DemoScenario.CASE_LIMIT_0701, List.of(firstOf(events, n -> type(n, "LimitLedgerOpened")
                && p(n, "policyNo").equals(s.caseContract(DemoScenario.CASE_LIMIT_0701).policyNo())).path("seq").asLong()));
        // 분급 경계 — 12-31은 스케줄 없음, 01-01은 커브 스케줄
        String p1231 = s.caseContract(DemoScenario.CASE_CURVE_1231).policyNo();
        assertThat(events.stream().noneMatch(n -> type(n, "DeferralScheduled") && p(n, "policyNo").equals(p1231)
                && p(n, "reason").equals("CURVE"))).as(DemoScenario.CASE_CURVE_1231).isTrue();
        found.put(DemoScenario.CASE_CURVE_1231, List.of(firstOf(events, n -> type(n, "CommissionCalculated") && p(n, "policyNo").equals(p1231))
                .path("seq").asLong()));
        found.put(DemoScenario.CASE_CURVE_0101, List.of(firstOf(events, n -> type(n, "DeferralScheduled")
                && p(n, "policyNo").equals(s.caseContract(DemoScenario.CASE_CURVE_0101).policyNo())).path("seq").asLong()));
        // 재산정 — 감액 뒤 원장 재산정
        String repriced = ledgerOf.get(s.caseContract(DemoScenario.CASE_REPRICE).policyNo());
        found.put(DemoScenario.CASE_REPRICE, List.of(firstOf(events, n -> type(n, "LimitLedgerRebased") && p(n, "ledgerId").equals(repriced))
                .path("seq").asLong()));
        // 채권 이월 — OPEN으로 생긴 채권이 뒤에 상계된다
        JsonNode open = firstOf(events, n -> type(n, "ClawbackReceivableUpdated") && p(n, "status").equals("OPEN")
                && events.stream().anyMatch(m -> type(m, "ClawbackReceivableUpdated") && p(m, "receivableId").equals(p(n, "receivableId"))
                && !p(m, "status").equals("OPEN")));
        JsonNode offset = firstOf(events, n -> type(n, "ClawbackReceivableUpdated") && p(n, "receivableId").equals(p(open, "receivableId"))
                && !p(n, "status").equals("OPEN"));
        found.put(DemoScenario.CASE_RECEIVABLE, List.of(open.path("seq").asLong(), offset.path("seq").asLong()));
        // 취소·재분개 짝 — reversal 라인과 그 뒤 같은 계약의 재분개 라인
        String rebook = s.caseContract(DemoScenario.CASE_REBOOK).policyNo();
        JsonNode reversal = firstOf(events, n -> type(n, "CommissionCalculated") && p(n, "policyNo").equals(rebook)
                && n.path("payload").has("reversalOfCalcId"));
        JsonNode rebooked = firstOf(events, n -> type(n, "CommissionCalculated") && p(n, "policyNo").equals(rebook)
                && n.path("seq").asLong() > reversal.path("seq").asLong() && !n.path("payload").has("reversalOfCalcId"));
        found.put(DemoScenario.CASE_REBOOK, List.of(reversal.path("seq").asLong(), rebooked.path("seq").asLong()));
        // 마감 후 조정
        String adjAgent = s.caseContract(DemoScenario.CASE_ADJUSTMENT).agentId();
        found.put(DemoScenario.CASE_ADJUSTMENT, List.of(firstOf(events, n -> type(n, "AdjustmentPosted") && p(n, "agentId").equals(adjAgent))
                .path("seq").asLong()));

        StringBuilder out = new StringBuilder("[\n");
        List<String> names = new ArrayList<>(found.keySet());
        for (String name : names) {
            DemoScenario.Contract c = s.caseContract(name.equals(DemoScenario.CASE_RECEIVABLE) ? DemoScenario.CASE_LIMIT_80 : name);
            String agent = name.equals(DemoScenario.CASE_RECEIVABLE) ? p(open, "agentId") : c.agentId();
            String policy = name.equals(DemoScenario.CASE_RECEIVABLE) ? "" : c.policyNo();
            out.append("  {\"case\":\"").append(name).append("\",\"agentId\":\"").append(agent).append("\",\"policyNo\":\"").append(policy)
                    .append("\",\"seqs\":").append(found.get(name).stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]")))
                    .append('}').append(name.equals(names.getLast()) ? "\n" : ",\n");
        }
        return out.append("]\n").toString();
    }

    private static List<JsonNode> all(OutboxEngine e) {
        List<JsonNode> out = new ArrayList<>();
        long after = 0;
        while (true) {
            List<JsonNode> page = e.eventsAfter(after);
            if (page.isEmpty()) {
                return out;
            }
            out.addAll(page);
            after = page.getLast().path("seq").asLong();
        }
    }

    private static byte[] statement(MockMvc m, String agentId, String ym, String mode, int status) throws Exception {
        byte[] body = m.perform(get("/internal/v1/agents/" + agentId + "/statements/" + ym + "?mode=" + mode)).andReturn().getResponse()
                .getContentAsByteArray();
        assertThat(m.perform(get("/internal/v1/agents/" + agentId + "/statements/" + ym + "?mode=" + mode)).andReturn().getResponse()
                .getStatus()).as(agentId + " " + ym + " " + mode + " " + new String(body, StandardCharsets.UTF_8)).isEqualTo(status);
        return body;
    }

    private static String entry(String agentId, String ym, String mode, byte[] body) {
        return "  {\"agentId\":\"" + agentId + "\",\"ym\":\"" + ym + "\",\"mode\":\"" + mode + "\",\"status\":200,\"response\":"
                + new String(body, StandardCharsets.UTF_8) + "}";
    }

    private static String sim(MockMvc m, String name, String path, String uri, String request, String schema) throws Exception {
        var r = m.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(request)).andReturn().getResponse();
        byte[] body = r.getContentAsByteArray();
        assertThat(r.getStatus()).as(name + " " + new String(body, StandardCharsets.UTF_8)).isEqualTo(200);
        assertThat(PortalContract.get().validateApiSchema(schema, JSON.readTree(body))).as(name).isEmpty();
        return "  {\"name\":\"" + name + "\",\"path\":\"" + path + "\",\"status\":200,\"request\":" + request + ",\"response\":"
                + new String(body, StandardCharsets.UTF_8) + "}";
    }

    private static boolean type(JsonNode n, String type) {
        return n.path("type").asText().equals(type);
    }

    private static String p(JsonNode n, String field) {
        return n.path("payload").path(field).asText();
    }

    private static JsonNode firstOf(List<JsonNode> events, Predicate<JsonNode> test) {
        return events.stream().filter(test).findFirst().orElseThrow(() -> new AssertionError("케이스 없음"));
    }

    private static JsonNode lastOf(List<JsonNode> events, Predicate<JsonNode> test) {
        return events.stream().filter(test).reduce((a, b) -> b).orElseThrow(() -> new AssertionError("케이스 없음"));
    }

    private static void file(Path dir, String name, String content, boolean update) throws IOException {
        Path p = dir.resolve(name);
        if (update) {
            Files.createDirectories(dir);
            Files.writeString(p, content, StandardCharsets.UTF_8);
        } else {
            assertThat(Files.exists(p)).as(p + " 없음 — ./gradlew :commission-api:test -Psamples.update=true 로 쓴다").isTrue();
            assertThat(Files.readString(p, StandardCharsets.UTF_8))
                    .as(name + " 가 엔진 출력과 다르다 — 엔진이 바뀌었으면 -Psamples.update=true 로 다시 쓰고 포털에 알린다").isEqualTo(content);
        }
    }
}
