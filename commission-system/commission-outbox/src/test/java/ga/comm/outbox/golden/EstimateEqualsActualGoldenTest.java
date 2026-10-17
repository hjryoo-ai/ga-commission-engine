package ga.comm.outbox.golden;

import com.fasterxml.jackson.databind.JsonNode;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.EventType;
import ga.comm.domain.type.RecipientType;
import ga.comm.outbox.fixture.OutboxEngine;
import ga.comm.outbox.fixture.SteppingClock;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2 C2 — <b>예상 = 실제</b>. 어떤 달에 잠정 명세(①)를 낸 뒤 새 업무 입력(정책 이벤트·정정) 없이 그 달을 마감·지급하면, 잠정 명세의
 * {@code gross}·{@code clawbackOffset}·{@code adjustment}는 {@code StatementConfirmed}의 것과, {@code withholdingTax}·{@code net}은
 * {@code PayoutCompleted}의 것과 같다. 다섯 케이스: ① 기본 ② 채권 상계 ③ 정정 포함 ④ 음수 순액 채권화 ⑤ 분급 도래(P1).
 * 케이스마다 이벤트 열을 {@code outbox-golden/estimate_cycle_<케이스>.jsonl}로 바이트 대조한다(E2 승인 Q8 — 포털이 ESTIMATED→CONFIRMED→PAID
 * 전이 골든으로 들인다). 대조 표는 {@code build/reports/outbox-estimate/estimate-vs-actual.md}.
 */
class EstimateEqualsActualGoldenTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 케이스 하나: 준비(대상 달 앞까지) → 그 달 잠정 명세 → 마감 → 지급. */
    record Case(String name, CloseYm month, BiConsumer<OutboxEngine, SteppingClock> setUp) {
    }

    static List<Case> cases() {
        return List.of(
                new Case("01_기본", CloseYm.of("202608"), (e, c) -> {
                    at(c, "2026-08-01T09:00");
                    e.calculator.process(EventFixtures.newContract());
                }),
                new Case("02_채권_상계", CloseYm.of("202610"), (e, c) -> {
                    at(c, "2026-08-01T09:00");
                    e.calculator.process(EventFixtures.newContract());
                    closeAndPay(e, c, CloseYm.of("202608"), "2026-09-05T18:00", "2026-09-10T10:00");
                    at(c, "2026-09-15T09:30");
                    e.calculator.process(EventFixtures.terminal(EventType.CANCEL, EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE,
                            LocalDate.of(2026, 9, 15), 2));
                    closeAndPay(e, c, CloseYm.of("202609"), "2026-10-05T18:00", "2026-10-10T10:00");   // 음수 순액 → 채권
                    at(c, "2026-10-01T09:00");
                    e.calculator.process(EventFixtures.newContract(new PolicyNo("POL-2026-0002"), LocalDate.of(2026, 10, 1),
                            Money.won(300_000), Map.of()));
                }),
                new Case("03_정정_포함", CloseYm.of("202609"), (e, c) -> {
                    at(c, "2026-08-01T09:00");
                    e.calculator.process(EventFixtures.newContract());
                    closeAndPay(e, c, CloseYm.of("202608"), "2026-09-05T18:00", "2026-09-10T10:00");
                    long fy = e.raw.calcs().findByCloseYm(CloseYm.of("202608")).stream()
                            .filter(r -> r.recipientType() == RecipientType.AGENT).findFirst().orElseThrow().calcId();
                    at(c, "2026-09-11T09:00");
                    e.calculator.process(EventFixtures.newContract(new PolicyNo("POL-2026-0002"), LocalDate.of(2026, 9, 11),
                            Money.won(300_000), Map.of()));
                    at(c, "2026-09-12T11:00");
                    e.adjustmentService.adjust(fy, "소급 요율 정정", Money.won(-45_000), "정산팀장");   // 다음 열린 달(202609) 귀속
                }),
                new Case("04_음수_순액_채권화", CloseYm.of("202609"), (e, c) -> {
                    at(c, "2026-08-01T09:00");
                    e.calculator.process(EventFixtures.newContract());
                    closeAndPay(e, c, CloseYm.of("202608"), "2026-09-05T18:00", "2026-09-10T10:00");
                    at(c, "2026-09-15T09:30");
                    e.calculator.process(EventFixtures.terminal(EventType.CANCEL, EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE,
                            LocalDate.of(2026, 9, 15), 2));
                }),
                new Case("05_분급_도래", CloseYm.of("202801"), (e, c) -> {
                    at(c, "2027-01-15T09:00");
                    e.calculator.process(EventFixtures.newContract(new PolicyNo("POL-2027-0001"), LocalDate.of(2027, 1, 15),
                            Money.won(300_000), Map.of()));
                }));
    }

    private static void at(SteppingClock c, String local) {
        c.set(OffsetDateTime.parse(local + ":00+09:00"));
    }

    private static void closeAndPay(OutboxEngine e, SteppingClock c, CloseYm ym, String closeAt, String payAt) {
        at(c, closeAt);
        e.closeService.close(ym, "정산담당");
        at(c, payAt);
        e.payoutService.run(ym, 1);
    }

    /** 대상 달 m의 잠정 명세 → 마감 → 지급 시각: 다음 달 1일 02:10 / 5일 18:00 / 10일 10:00. */
    static List<JsonNode> run(Case k) {
        SteppingClock clock = new SteppingClock(OffsetDateTime.parse("2026-01-01T00:00:00+09:00").toInstant(), SEOUL);
        OutboxEngine e = PortalA1StreamTest.engine(clock);
        k.setUp().accept(e, clock);
        LocalDate next = LocalDate.of(Integer.parseInt(k.month().value().substring(0, 4)), Integer.parseInt(k.month().value().substring(4)), 1)
                .plusMonths(1);
        clock.set(next.atTime(2, 10).atZone(SEOUL).toOffsetDateTime());
        assertThat(e.estimateAndPublish(k.month())).as("대상 설계사가 있다").isNotEmpty();
        clock.set(next.withDayOfMonth(5).atTime(18, 0).atZone(SEOUL).toOffsetDateTime());
        e.closeService.close(k.month(), "정산담당");
        clock.set(next.withDayOfMonth(10).atTime(10, 0).atZone(SEOUL).toOffsetDateTime());
        e.payoutService.run(k.month(), 1);
        return e.eventsAfter(0);
    }

    private static JsonNode last(List<JsonNode> events, String type, CloseYm ym) {
        JsonNode found = null;
        for (JsonNode n : events) {
            if (n.path("type").asText().equals(type) && n.path("payload").path("closeYm").asText().equals(ym.value())
                    && n.path("payload").path("agentId").asText().equals(EventFixtures.AGENT_A.value())) {
                found = n.path("payload");
            }
        }
        assertThat(found).as(type + " " + ym).isNotNull();
        return found;
    }

    @TestFactory
    Stream<DynamicTest> 잠정_명세는_새_입력_없이_마감_지급한_값과_같다() {
        StringBuilder md = new StringBuilder("| 케이스 | 달 | 필드 | 잠정 명세 | 확정·지급 |\n|---|---|---|---|---|\n");
        List<DynamicTest> tests = new ArrayList<>();
        for (Case k : cases()) {
            tests.add(DynamicTest.dynamicTest(k.name(), () -> {
                List<JsonNode> events = run(k);
                JsonNode est = last(events, "StatementEstimated", k.month());
                JsonNode conf = last(events, "StatementConfirmed", k.month());
                JsonNode paid = last(events, "PayoutCompleted", k.month());
                for (String f : List.of("gross", "clawbackOffset", "adjustment")) {
                    md.append("| ").append(k.name()).append(" | ").append(k.month().value()).append(" | ").append(f).append(" | ")
                            .append(est.path(f).asLong()).append(" | ").append(conf.path(f).asLong()).append(" |\n");
                    assertThat(est.path(f).asLong()).as(k.name() + " " + f).isEqualTo(conf.path(f).asLong());
                }
                for (String f : List.of("withholdingTax", "net")) {
                    md.append("| ").append(k.name()).append(" | ").append(k.month().value()).append(" | ").append(f).append(" | ")
                            .append(est.path(f).asLong()).append(" | ").append(paid.path(f).asLong()).append(" |\n");
                    assertThat(est.path(f).asLong()).as(k.name() + " " + f).isEqualTo(paid.path(f).asLong());
                }
                List<String> lines = events.stream().map(JsonNode::toString).toList();
                assertSnapshot("estimate_cycle_" + k.name(), lines);
                write(md);
            }));
        }
        return tests.stream();
    }

    private static void write(StringBuilder md) {
        try {
            Path out = Path.of(System.getProperty("user.dir"), "build", "reports", "outbox-estimate", "estimate-vs-actual.md");
            Files.createDirectories(out.getParent());
            Files.writeString(out, md, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void assertSnapshot(String name, List<String> lines) {
        Path file = PortalA1StreamTest.snapshotDir().resolve(name + ".jsonl");
        String actual = String.join("\n", lines) + "\n";
        try {
            if (Boolean.getBoolean("outbox.golden.update")) {
                Files.writeString(file, actual, StandardCharsets.UTF_8);
                return;
            }
            assertThat(file).as("스냅샷 " + file + " — 처음이면 -PupdateOutboxGolden").exists();
            assertThat(Files.readString(file, StandardCharsets.UTF_8)).as("잠정 명세 사이클 " + name).isEqualTo(actual);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
