package ga.comm.outbox.golden;

import com.fasterxml.jackson.databind.JsonNode;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.calc.fixture.InMemoryCommCalcStore;
import ga.comm.calc.fixture.InMemoryPolicyEventStore;
import ga.comm.clawback.fixture.InMemoryClawbackReceivableStore;
import ga.comm.deferral.fixture.InMemoryDeferralScheduleStore;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.EventType;
import ga.comm.limit.fixture.InMemoryLimitLedgerStore;
import ga.comm.outbox.contract.PortalContract;
import ga.comm.outbox.fixture.InMemoryOutboxStore;
import ga.comm.outbox.fixture.OutboxEngine;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.settlement.fixture.InMemoryAdjustmentStore;
import ga.comm.settlement.fixture.InMemoryAgentSettlementStore;
import ga.comm.settlement.fixture.InMemoryPayoutStatementStore;
import ga.comm.settlement.fixture.InMemorySettleCloseStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E1 C10 — 포털 부록 A-1(샘플 seq 1~13)과 엔진이 같은 입력(월납 30만원·2026-08-01 체결·시책 100만원·2027-02-15 해약 7회차)에서 내는 이벤트의
 * 대조. 금액은 다르다(엔진 시드 FY = 7.0 × 0.9 → 1,890,000, 샘플 2,800,000 — 샘플은 계약 예시). 이 시험은 ① 차이 표를 산출물로 쓰고
 * ({@code build/reports/outbox-a1/a1-diff.md}) ② <b>타입 순서</b>를 따로 단언한다(E1 승인 C10 — 금액이 달라도 순서가 같으면 그것이 증거).
 */
class PortalA1ComparisonTest {

    /** 엔진이 발행하지 않는 샘플 타입 — 이유가 있는 것만(E2 범위 StatementEstimated, P1 LIMIT_DEFER 스케줄과 그 취소). */
    private static final Set<String> NOT_EMITTED = Set.of("StatementEstimated", "DeferralScheduled", "DeferralStatusChanged");

    @Test
    void A1_대조() throws IOException {
        InMemoryOutboxStore outbox = new InMemoryOutboxStore();
        OutboxEngine e = new OutboxEngine(RuleFixtures.standardRules(), CalcTestHarness.standardDirectory(),
                new OutboxEngine.Raw(new InMemoryPolicyEventStore(), new InMemoryCommCalcStore(), new InMemoryLimitLedgerStore(),
                        new InMemoryDeferralScheduleStore(), new InMemoryClawbackReceivableStore(), new InMemoryAdjustmentStore(),
                        new InMemorySettleCloseStore(), new InMemoryAgentSettlementStore(), new InMemoryPayoutStatementStore(), outbox),
                OutboxGoldenTest.CLOCK, OutboxEngine.sequentialIds());
        e.calculator.process(EventFixtures.newContract(EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE, Money.won(300_000),
                Map.of("incentive_amount", "1000000")));
        e.closeService.close(CloseYm.of("202608"), "정산담당");
        e.payoutService.run(CloseYm.of("202608"), 1);
        e.calculator.process(EventFixtures.terminal(EventType.CANCEL, EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE,
                LocalDate.of(2027, 2, 15), 7));
        List<JsonNode> engine = e.eventsAfter(0);

        List<JsonNode> samples = new ArrayList<>();
        for (String name : PortalContract.sampleNames()) {
            JsonNode s = PortalContract.portalFile("events/v1/samples/" + name);
            if (s.path("seq").asLong() <= 13) {
                samples.add(s);
            }
        }
        assertThat(samples).hasSize(13);

        // ② 타입 순서: 샘플에서 엔진이 내지 않는 타입(이유 있는 것)을 빼면 엔진의 순서와 같아야 한다 — 한 계산 안의 라인 둘(FY·시책)은
        //    엔진에서 계산 이벤트 둘 다음 전기 둘(저장 후 훅이 저장 뒤 한꺼번에 전기)이다. 샘플은 두 계산으로 나눠 계산·전기를 번갈아 둔다.
        List<String> sampleTypes = samples.stream().map(s -> s.path("type").asText()).filter(t -> !NOT_EMITTED.contains(t)).toList();
        List<String> engineTypes = engine.stream().map(n -> n.path("type").asText()).toList();
        assertThat(sampleTypes).containsExactly("LimitLedgerOpened", "CommissionCalculated", "LimitLedgerPosted",
                "CommissionCalculated", "LimitLedgerPosted", "StatementConfirmed", "PayoutCompleted", "PolicyEventObserved",
                "CommissionCalculated", "LimitLedgerPosted");
        assertThat(engineTypes).containsExactly("LimitLedgerOpened", "CommissionCalculated", "CommissionCalculated",
                "LimitLedgerPosted", "LimitLedgerPosted", "StatementConfirmed", "PayoutCompleted", "PolicyEventObserved",
                "CommissionCalculated", "LimitLedgerPosted");
        // 같은 타입 다중집합, 같은 사실 단위 순서(개설 → 계산·전기 → 확정 → 지급 → 해약 관측 → 환수 계산 → 환수 전기). 다른 곳은 한 곳 —
        // 같은 계산의 두 라인이 계산·전기를 번갈아 두지 않는다.
        assertThat(engineTypes).containsExactlyInAnyOrderElementsOf(sampleTypes);

        // ① 차이 표
        StringBuilder md = new StringBuilder("| seq | 샘플 타입 | 샘플 값 | 엔진 | 차이 이유 |\n|---|---|---|---|---|\n");
        for (JsonNode s : samples) {
            md.append("| ").append(s.path("seq").asLong()).append(" | ").append(s.path("type").asText()).append(" | ")
                    .append(key(s.path("payload"))).append(" | ").append(engineSide(s, engine)).append(" |\n");
        }
        Path out = Path.of(System.getProperty("user.dir"), "build", "reports", "outbox-a1", "a1-diff.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md, StandardCharsets.UTF_8);

        JsonNode fy = engine.get(1).path("payload");
        assertThat(fy.path("amount").asLong()).isEqualTo(1_890_000);
        JsonNode incentive = engine.get(2).path("payload");
        assertThat(incentive.path("commType").asText()).isEqualTo("INCENTIVE");
        assertThat(incentive.path("limitCutAmt").asLong()).isZero();   // 1,890,000 + 1,000,000 < 3,600,000 — 샘플은 200,000 삭감
    }

    private static String key(JsonNode p) {
        List<String> parts = new ArrayList<>();
        for (String f : List.of("commType", "amount", "limitCutAmt", "limitCutPolicy", "postingSeq", "accumAfter", "limitAmount", "gross",
                "clawbackOffset", "net", "paidAmount", "eventType", "reason", "to")) {
            if (p.has(f)) {
                parts.add(f + "=" + p.path(f).asText());
            }
        }
        return String.join(", ", parts);
    }

    private static String engineSide(JsonNode sample, List<JsonNode> engine) {
        String type = sample.path("type").asText();
        return switch (type) {
            case "StatementEstimated" -> "없음 | 잠정 명세는 E2(동기 API) 범위 — E1 피드 미발행";
            case "DeferralScheduled" -> "없음 | E1 P1: DEFER_AFTER_FY는 삭감과 같게 동작, 이연 스케줄을 만들지 않는다(엔진 §11)";
            case "DeferralStatusChanged" -> "없음 | 취소할 LIMIT_DEFER 스케줄이 없다(P1). 사유 어휘도 다르다(샘플 POLICY_CANCELLED, 엔진 POLICY_TERMINATED)";
            default -> {
                long nth = sampleOrdinal(sample);
                List<JsonNode> sameType = engine.stream().filter(n -> n.path("type").asText().equals(type)).toList();
                JsonNode match = nth <= sameType.size() ? sameType.get((int) nth - 1) : null;
                yield match == null ? "없음 | —" : key(match.path("payload")) + " (seq " + match.path("seq").asLong() + ") | "
                        + reason(type);
            }
        };
    }

    /** 이 샘플이 같은 타입 가운데 몇 번째인가(1부터). */
    private static long sampleOrdinal(JsonNode sample) {
        return switch (sample.path("seq").asInt()) {
            case 2, 3 -> 1;
            case 4, 5 -> 2;
            case 11 -> 3;
            case 12 -> 3;
            default -> 1;
        };
    }

    private static String reason(String type) {
        return switch (type) {
            case "CommissionCalculated", "LimitLedgerPosted" -> "엔진 시드 FY 7.0×지급률 0.9 = 1,890,000(샘플 2,800,000), 합이 한도 미만이라 시책 삭감 없음; 환수는 엔진 환수율표(7~12회차 40%)";
            case "StatementConfirmed" -> "샘플은 v1.1 이전 모양(세액·순액 포함) — 엔진은 마감 시점에 참인 것만(K4), 금액은 위 계산 차이";
            case "PayoutCompleted" -> "엔진은 확장 필드(runSeq·gross·…·net)를 더 싣는다(계약 선택 필드), 금액은 위 계산 차이";
            default -> "같은 사실";
        };
    }
}
