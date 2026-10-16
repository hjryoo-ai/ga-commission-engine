package ga.comm.outbox.golden;

import com.fasterxml.jackson.databind.JsonNode;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.calc.store.PolicyEventStore;
import ga.comm.deferral.DeferralScheduleStore;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.EventType;
import ga.comm.golden.GoldenCase;
import ga.comm.golden.GoldenComparator;
import ga.comm.golden.GoldenEngine;
import ga.comm.limit.LimitLedgerStore;
import ga.comm.outbox.EnvelopeJson;
import ga.comm.outbox.StoredEvent;
import ga.comm.outbox.fixture.InMemoryOutboxStore;
import ga.comm.outbox.fixture.OutboxEngine;
import ga.comm.outbox.fixture.ValidatingOutboxStore;
import ga.comm.outbox.publish.OutboxCommCalcStore;
import ga.comm.outbox.publish.OutboxDeferralScheduleStore;
import ga.comm.outbox.publish.OutboxLimitLedgerStore;
import ga.comm.outbox.publish.OutboxPolicyEventStore;
import ga.comm.outbox.publish.OutboxPublisher;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E1 C10 — 이벤트 골든. ① 골든셋 24건 전부를 발행 데코레이터를 끼운 엔진으로 다시 돌려 업무 결과가 그대로이고(비교기 불일치 0) 모든 이벤트가
 * 포털 계약을 통과함을 본다. ② 케이스 01·04·07·11과 마감·지급 사이클의 이벤트 열(Envelope, 순번 포함)을 스냅샷
 * {@code src/test/resources/outbox-golden/<케이스>.jsonl}과 바이트 대조한다. 갱신은 {@code -PupdateOutboxGolden}(검토 뒤 커밋).
 * 케이스 원본은 commission-settlement의 시험 리소스 — 이 태스크의 Gradle 입력으로 선언돼 있다(engine.goldenCasesDir).
 */
class OutboxGoldenTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-05T09:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final Set<String> SNAPSHOT_CASES = Set.of("01_신계약_기본", "04_시책_라인_편입", "07_한도_임박_초과_삭감", "11_해약_7회차_환수");

    private static Path casesDir() {
        String dir = System.getProperty("engine.goldenCasesDir");
        assertThat(dir).as("engine.goldenCasesDir(Gradle이 준다)").isNotNull();
        return Path.of(dir);
    }

    private static Path snapshotDir() {
        String dir = System.getProperty("engine.outboxGoldenDir");
        assertThat(dir).as("engine.outboxGoldenDir(Gradle이 준다)").isNotNull();
        return Path.of(dir);
    }

    private static List<Path> caseDirs() {
        try (Stream<Path> s = Files.list(casesDir())) {
            return s.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().startsWith("_"))
                    .filter(p -> Files.exists(p.resolve("case.csv"))).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 결정론 발행자(고정 시계·순차 eventId) 위에 감싸는 배선. */
    static final class Publishing implements GoldenEngine.Wiring {
        final InMemoryOutboxStore outbox = new InMemoryOutboxStore();
        final OutboxPublisher publisher = new OutboxPublisher(new ValidatingOutboxStore(outbox), CLOCK, OutboxEngine.sequentialIds());

        @Override
        public PolicyEventStore events(PolicyEventStore store) {
            return new OutboxPolicyEventStore(store, publisher);
        }

        @Override
        public CommCalcStore calcs(CommCalcStore store, PolicyEventStore events) {
            return new OutboxCommCalcStore(store, events, publisher);
        }

        @Override
        public LimitLedgerStore ledgers(LimitLedgerStore store) {
            return new OutboxLimitLedgerStore(store, publisher);
        }

        @Override
        public DeferralScheduleStore schedules(DeferralScheduleStore store) {
            return new OutboxDeferralScheduleStore(store, publisher);
        }

        List<String> lines() {
            outbox.assignPending(100_000);
            List<String> out = new ArrayList<>();
            for (StoredEvent e : outbox.readAssignedAfter(0, 100_000)) {
                out.add(EnvelopeJson.render(e).toString());
            }
            return out;
        }
    }

    @TestFactory
    Stream<DynamicTest> 골든셋_전부를_발행_데코레이터로() {
        List<Path> dirs = caseDirs();
        assertThat(dirs).hasSizeGreaterThanOrEqualTo(24);
        return dirs.stream().map(dir -> DynamicTest.dynamicTest(dir.getFileName().toString(), () -> {
            GoldenCase gc = GoldenCase.load(dir);
            Publishing wiring = new Publishing();
            GoldenEngine engine = new GoldenEngine(gc, wiring);
            engine.run();
            assertThat(new GoldenComparator(gc, engine).mismatches()).as("데코레이터가 업무 결과를 바꾸지 않는다").isEmpty();
            List<String> lines = wiring.lines();
            if (SNAPSHOT_CASES.contains(dir.getFileName().toString())) {
                assertSnapshot(dir.getFileName().toString(), lines);
            }
        }));
    }

    /** 마감·지급 사이클(골든 실행기에 없는 단계): 신계약 → 마감 → 지급 → 해약 환수 → 마감 → 채권화 → 다른 계약 → 마감 → 상계·지급. */
    @Test
    void 마감_지급_사이클() {
        InMemoryOutboxStore outbox = new InMemoryOutboxStore();
        OutboxEngine e = new OutboxEngine(ga.comm.rule.fixture.RuleFixtures.standardRules(),
                ga.comm.calc.fixture.CalcTestHarness.standardDirectory(),
                new OutboxEngine.Raw(new ga.comm.calc.fixture.InMemoryPolicyEventStore(), new ga.comm.calc.fixture.InMemoryCommCalcStore(),
                        new ga.comm.limit.fixture.InMemoryLimitLedgerStore(), new ga.comm.deferral.fixture.InMemoryDeferralScheduleStore(),
                        new ga.comm.clawback.fixture.InMemoryClawbackReceivableStore(), new ga.comm.settlement.fixture.InMemoryAdjustmentStore(),
                        new ga.comm.settlement.fixture.InMemorySettleCloseStore(), new ga.comm.settlement.fixture.InMemoryAgentSettlementStore(),
                        new ga.comm.settlement.fixture.InMemoryPayoutStatementStore(), outbox),
                CLOCK, OutboxEngine.sequentialIds());
        e.calculator.process(EventFixtures.newContract());
        e.closeService.close(CloseYm.of("202608"), "정산담당");
        e.payoutService.run(CloseYm.of("202608"), 1);
        e.calculator.process(EventFixtures.terminal(EventType.CANCEL, EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE,
                LocalDate.of(2026, 9, 15), 2));
        e.closeService.close(CloseYm.of("202609"), "정산담당");
        e.payoutService.run(CloseYm.of("202609"), 1);
        e.calculator.process(EventFixtures.newContract(new PolicyNo("POL-2026-0002"), LocalDate.of(2026, 10, 1), Money.won(300_000), Map.of()));
        long fy = e.raw.calcs().findByCloseYm(CloseYm.of("202610")).stream()
                .filter(r -> r.recipientType() == ga.comm.domain.type.RecipientType.AGENT).findFirst().orElseThrow().calcId();
        e.closeService.close(CloseYm.of("202610"), "정산담당");
        e.adjustmentService.adjust(fy, "소급 요율 정정", Money.won(-45_000), "정산팀장");
        e.payoutService.run(CloseYm.of("202610"), 1);
        e.closeService.close(CloseYm.of("202611"), "정산담당");

        List<String> lines = new ArrayList<>();
        for (JsonNode n : e.eventsAfter(0)) {
            lines.add(n.toString());
        }
        assertSnapshot("cycle_마감_지급_채권_정정", lines);
    }

    private static void assertSnapshot(String name, List<String> lines) {
        Path file = snapshotDir().resolve(name + ".jsonl");
        String actual = String.join("\n", lines) + "\n";
        try {
            if (Boolean.getBoolean("outbox.golden.update")) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, actual, StandardCharsets.UTF_8);
                return;
            }
            assertThat(file).as("스냅샷 " + file + " — 처음이면 -PupdateOutboxGolden로 만들고 검토 뒤 커밋").exists();
            assertThat(Files.readString(file, StandardCharsets.UTF_8)).as("이벤트 골든 " + name).isEqualTo(actual);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
