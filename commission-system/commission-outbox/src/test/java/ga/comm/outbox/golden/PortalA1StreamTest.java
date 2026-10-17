package ga.comm.outbox.golden;

import com.fasterxml.jackson.databind.JsonNode;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.InMemoryCommCalcStore;
import ga.comm.calc.fixture.InMemoryPolicyEventStore;
import ga.comm.clawback.fixture.InMemoryClawbackReceivableStore;
import ga.comm.deferral.fixture.InMemoryDeferralScheduleStore;
import ga.comm.limit.fixture.InMemoryLimitLedgerStore;
import ga.comm.outbox.fixture.InMemoryOutboxStore;
import ga.comm.outbox.fixture.OutboxEngine;
import ga.comm.outbox.fixture.SteppingClock;
import ga.comm.outbox.scenario.A1Scenario;
import ga.comm.outbox.scenario.A1ScenarioRunner;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.settlement.fixture.InMemoryAdjustmentStore;
import ga.comm.settlement.fixture.InMemoryAgentSettlementStore;
import ga.comm.settlement.fixture.InMemoryPayoutStatementStore;
import ga.comm.settlement.fixture.InMemorySettleCloseStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2 ⓪·C9 — 포털 정본 A-1 스트림(월납 150,000). 시나리오({@link A1Scenario#portal150000()})를 인메모리 엔진(발행 데코레이터 + 전역 검증기 —
 * 모든 이벤트가 포털 계약을 통과해야 적재된다)에 단계 시계(Q9)·순차 eventId로 돌려 {@code outbox-golden/a1_portal_150000.jsonl}과 바이트
 * 대조한다. 포털이 이 파일을 바이트 그대로 계약 샘플로 들이므로 <b>첫 판은 커밋 뒤 바꾸지 않는다</b> — 갱신(-PupdateOutboxGolden)은 다른 파일에만.
 */
class PortalA1StreamTest {

    static OutboxEngine engine(SteppingClock clock) {
        return new OutboxEngine(RuleFixtures.standardRules(), CalcTestHarness.standardDirectory(),
                new OutboxEngine.Raw(new InMemoryPolicyEventStore(), new InMemoryCommCalcStore(), new InMemoryLimitLedgerStore(),
                        new InMemoryDeferralScheduleStore(), new InMemoryClawbackReceivableStore(), new InMemoryAdjustmentStore(),
                        new InMemorySettleCloseStore(), new InMemoryAgentSettlementStore(), new InMemoryPayoutStatementStore(),
                        new InMemoryOutboxStore()),
                clock, OutboxEngine.sequentialIds());
    }

    static SteppingClock clock(A1Scenario s) {
        return new SteppingClock(s.times().calc().toInstant(), ZoneId.of("Asia/Seoul"));
    }

    static List<String> firstEdition() {
        A1Scenario s = A1Scenario.portal150000();
        SteppingClock clock = clock(s);
        OutboxEngine e = engine(clock);
        A1ScenarioRunner.firstEdition(s, A1ScenarioRunner.of(e), clock);
        List<String> lines = new ArrayList<>();
        for (JsonNode n : e.eventsAfter(0)) {
            lines.add(n.toString());
        }
        return lines;
    }

    /** 두 번째 판(E2 ⓪, Q8) — 첫 판 + 해약 뒤 그 달(202702) 잠정 명세. */
    static List<String> secondEdition() {
        A1Scenario s = A1Scenario.portal150000();
        SteppingClock clock = clock(s);
        OutboxEngine e = engine(clock);
        A1ScenarioRunner.secondEdition(s, A1ScenarioRunner.of(e), clock);
        List<String> lines = new ArrayList<>();
        for (JsonNode n : e.eventsAfter(0)) {
            lines.add(n.toString());
        }
        return lines;
    }

    @Test
    void 두_번째_판은_파일과_바이트가_같다() {
        assertSnapshot(A1Scenario.SECOND_EDITION, secondEdition());
    }

    /** Q8·C9 — 두 번째 판은 첫 판 뒤에 붙인다: 첫 판 파일의 바이트가 두 번째 판 파일의 앞부분 그대로(seq·eventId는 위치값 — P6). 주입 S10. */
    @Test
    void 첫_판은_두_번째_판의_앞부분이다() throws IOException {
        byte[] first = Files.readAllBytes(snapshotDir().resolve(A1Scenario.FIRST_EDITION + ".jsonl"));
        byte[] second = Files.readAllBytes(snapshotDir().resolve(A1Scenario.SECOND_EDITION + ".jsonl"));
        assertThat(second.length).isGreaterThan(first.length);
        assertThat(java.util.Arrays.copyOf(second, first.length)).as("첫 판 = 두 번째 판의 앞부분(바이트)").isEqualTo(first);
        List<String> tail = secondEdition().subList(firstEdition().size(), secondEdition().size());
        assertThat(tail).hasSize(1);
        JsonNode est = parse(tail.getFirst());
        assertThat(est.path("type").asText()).isEqualTo("StatementEstimated");
        assertThat(est.path("seq").asLong()).isEqualTo(11);
        JsonNode p = est.path("payload");
        assertThat(p.path("closeYm").asText()).isEqualTo("202702");
        assertThat(p.path("gross").asLong()).isZero();
        assertThat(p.path("clawbackOffset").asLong()).isEqualTo(-720_000);
        assertThat(p.path("withholdingTax").asLong()).isZero();
        assertThat(p.path("net").asLong()).as("지급할 것이 없다(순액 음수 — 지급 런과 같은 산식)").isZero();
        assertThat(p.path("carriedReceivable").asLong()).as("음수 순액 → 채권 예정").isEqualTo(720_000);
        assertThat(p.path("basisSeq").asLong()).as("부여 뒤 최대 순번 — 첫 판 10건").isEqualTo(10);
        assertThat(p.path("asOf").asText()).isEqualTo("2027-02-16T02:10:00+09:00");
    }

    @Test
    void 첫_판은_파일과_바이트가_같다() {
        assertSnapshot(A1Scenario.FIRST_EDITION, firstEdition());
    }

    /** 포털 Phase 1 수용 심사 §3-3·E2 계획 A-1 입력값 확인의 값(두 계획의 손계산)을 엔진이 낸다. */
    @Test
    void 첫_판의_값() {
        List<JsonNode> ev = firstEdition().stream().map(PortalA1StreamTest::parse).toList();
        assertThat(ev.stream().map(n -> n.path("type").asText())).containsExactly("LimitLedgerOpened", "CommissionCalculated",
                "CommissionCalculated", "LimitLedgerPosted", "LimitLedgerPosted", "StatementConfirmed", "PayoutCompleted",
                "PolicyEventObserved", "CommissionCalculated", "LimitLedgerPosted");
        assertThat(ev.get(0).path("payload").path("limitAmount").asLong()).isEqualTo(1_800_000);
        assertThat(ev.get(1).path("payload").path("commType").asText()).isEqualTo("FY_COMM");
        assertThat(ev.get(1).path("payload").path("amount").asLong()).isEqualTo(945_000);
        JsonNode incentive = ev.get(2).path("payload");
        assertThat(incentive.path("commType").asText()).isEqualTo("INCENTIVE");
        assertThat(incentive.path("amount").asLong()).isEqualTo(855_000);
        assertThat(incentive.path("limitCutAmt").asLong()).isEqualTo(145_000);
        assertThat(ev.get(4).path("payload").path("accumAfter").asLong()).isEqualTo(1_800_000);
        assertThat(ev.get(5).path("payload").path("gross").asLong()).isEqualTo(1_800_000);
        JsonNode paid = ev.get(6).path("payload");
        assertThat(paid.path("withholdingTax").asLong()).isEqualTo(59_400);
        assertThat(paid.path("net").asLong()).isEqualTo(1_740_600);
        assertThat(paid.path("paidAmount").asLong()).isEqualTo(1_740_600);
        JsonNode clawback = ev.get(8).path("payload");
        assertThat(clawback.path("commType").asText()).isEqualTo("CLAWBACK");
        assertThat(clawback.path("amount").asLong()).isEqualTo(-720_000);
        assertThat(clawback.path("closeYm").asText()).isEqualTo("202702");
        assertThat(ev.get(9).path("payload").path("accumAfter").asLong()).isEqualTo(1_080_000);
        // 단계 시계(Q9): 사실마다 업무 시각
        assertThat(ev.get(1).path("occurredAt").asText()).isEqualTo("2026-08-01T09:00:00+09:00");
        assertThat(ev.get(5).path("occurredAt").asText()).isEqualTo("2026-09-05T18:00:00+09:00");
        assertThat(ev.get(6).path("occurredAt").asText()).isEqualTo("2026-09-10T10:00:00+09:00");
        assertThat(ev.get(7).path("occurredAt").asText()).isEqualTo("2027-02-15T09:30:00+09:00");
    }

    static JsonNode parse(String line) {
        try {
            return JSON.readTree(line);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    static Path snapshotDir() {
        String dir = System.getProperty("engine.outboxGoldenDir");
        assertThat(dir).as("engine.outboxGoldenDir(Gradle이 준다)").isNotNull();
        return Path.of(dir);
    }

    static void assertSnapshot(String name, List<String> lines) {
        Path file = snapshotDir().resolve(name + ".jsonl");
        String actual = String.join("\n", lines) + "\n";
        try {
            if (Boolean.getBoolean("outbox.golden.update") && !Files.exists(file)) {
                Files.writeString(file, actual, StandardCharsets.UTF_8);   // 처음 한 번만 — 이미 있으면 갱신하지 않는다(포털이 바이트로 들인다)
                return;
            }
            assertThat(file).as("스냅샷 " + file).exists();
            assertThat(Files.readString(file, StandardCharsets.UTF_8)).as("A-1 스트림 " + name).isEqualTo(actual);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
