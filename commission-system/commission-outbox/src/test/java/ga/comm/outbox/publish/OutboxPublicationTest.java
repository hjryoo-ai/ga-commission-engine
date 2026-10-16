package ga.comm.outbox.publish;

import com.fasterxml.jackson.databind.JsonNode;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.deferral.DeferralTransitionReason;
import ga.comm.deferral.ScheduleEntry;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.EventType;
import ga.comm.domain.type.RecipientType;
import ga.comm.limit.LimitLedger;
import ga.comm.calc.net.NetAmountCalculator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 발행 지점 표(설계서 §6.8)의 각 행이 실제 엔진 경로에서 나오는지 — 인메모리 조립({@link OutboxScenario}), 모든 이벤트는 전역 검증기를 통과한다(C1).
 * C4(금액 정합)·C5(마감·지급 집계)·C6(ORG 제외)·D-7(정책 이벤트 매핑)을 엔진 경로로 본다.
 */
class OutboxPublicationTest {

    private static final PolicyNo POLICY_2 = new PolicyNo("POL-2026-0002");
    private static final Set<String> ORG_IDS = Set.of("T1", "B1", "H1");

    private static long sum(List<JsonNode> payloads, String field) {
        return payloads.stream().mapToLong(p -> p.path(field).asLong()).sum();
    }

    @Test
    void 신계약은_설계사_라인만_계산_이벤트가_되고_원장이_한_번_열린다() {
        OutboxScenario s = new OutboxScenario();
        List<CommCalcRecord> produced = s.calculator.process(EventFixtures.newContract());

        assertThat(produced).anyMatch(r -> r.recipientType() == RecipientType.ORG);   // 조직 오버라이드 라인은 있다
        List<JsonNode> calcs = s.payloads(OutboxCommCalcStore.TYPE);
        assertThat(calcs).hasSize((int) produced.stream().filter(r -> r.recipientType() == RecipientType.AGENT).count());
        assertThat(calcs).allSatisfy(p -> assertThat(p.path("agentId").asText()).isEqualTo("A-1001"));
        JsonNode fy = calcs.get(0);
        assertThat(fy.path("commType").asText()).isEqualTo("FY_COMM");
        assertThat(fy.path("amount").asLong()).isEqualTo(1_890_000);
        assertThat(fy.path("basis").path("rate").asText()).isEqualTo("7");
        assertThat(fy.path("basis").path("payoutRate").asText()).isEqualTo("0.9");
        assertThat(fy.path("productKey").asText()).isEqualTo("WHOLE-LIFE-20Y");
        assertThat(fy.path("policySummary").path("monthlyPremium").asLong()).isEqualTo(300_000);

        assertThat(s.events(OutboxPolicyEventStore.TYPE)).isEmpty();   // NEW는 PolicyEventObserved가 아니다(D-7)
        List<JsonNode> opened = s.payloads(OutboxLimitLedgerStore.OPENED);
        assertThat(opened).hasSize(1);
        assertThat(opened.get(0).path("limitAmount").asLong()).isEqualTo(3_600_000);
        assertThat(opened.get(0).path("limitMultiple").asText()).isEqualTo("12");
        List<JsonNode> posted = s.payloads(OutboxLimitLedgerStore.POSTED);
        assertThat(posted).extracting(p -> p.path("postingSeq").asInt()).containsExactly(1);
        assertThat(posted.get(0).path("accumAfter").asLong()).isEqualTo(1_890_000);
        assertThat(posted.get(0).path("calcId").asText()).isEqualTo(fy.path("calcId").asText());

        // 같은 원장에 두 번째 계산 — 개설은 다시 나오지 않고 전기 순번이 이어진다
        s.calculator.process(EventFixtures.payment(2, LocalDate.of(2026, 9, 1)));
        assertThat(s.events(OutboxLimitLedgerStore.OPENED)).hasSize(1);
        List<JsonNode> posted2 = s.payloads(OutboxLimitLedgerStore.POSTED);
        assertThat(posted2).extracting(p -> p.path("postingSeq").asInt()).containsExactly(1, 2);
        assertThat(posted2.get(1).path("accumAfter").asLong())
                .isEqualTo(1_890_000 + posted2.get(1).path("amount").asLong());
        assertThat(s.payloads(OutboxCommCalcStore.TYPE).get(1).path("basis").path("installmentNo").asInt()).isEqualTo(2);
        LimitLedger ledger = s.rawLedgers.find(EventFixtures.POLICY_1, EventFixtures.AGENT_A).orElseThrow();
        assertThat(posted2.get(1).path("accumAfter").asLong()).isEqualTo(ledger.accumPaid().toLong());
    }

    /** C4: amount + limitCutAmt = 게이트 전 요청액, 삭감 정책은 룰의 over_limit_action. */
    @Test
    void 한도_삭감은_지급액과_삭감액으로_나뉘어_합이_요청액이다() {
        OutboxScenario s = new OutboxScenario();
        s.calculator.process(EventFixtures.newContract(EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE, Money.won(300_000),
                Map.of("incentive_amount", "3000000")));

        List<JsonNode> calcs = s.payloads(OutboxCommCalcStore.TYPE);
        JsonNode cut = calcs.stream().filter(p -> p.path("limitCutAmt").asLong() > 0).findFirst().orElseThrow();
        CommCalcRecord rec = s.rawCalcs.findById(Long.parseLong(cut.path("calcId").asText())).orElseThrow();
        long requested = Long.parseLong(PortalPayloads.traceValue(rec.calcTrace(), "LIMIT_GATE_V1", "requested",
                rec.commType().value()).orElseThrow());
        assertThat(cut.path("amount").asLong() + cut.path("limitCutAmt").asLong()).isEqualTo(requested);
        assertThat(cut.path("limitCutPolicy").asText()).isEqualTo("DEFER_AFTER_FY");   // 룰 값 그대로(E1 P1 — 이연 스케줄은 없다)
        assertThat(s.events(OutboxDeferralScheduleStore.SCHEDULED)).isEmpty();
        assertThat(sum(calcs, "amount")).isEqualTo(3_600_000);   // 한도까지만 지급
        assertThat(calcs.stream().filter(p -> p.path("limitCutAmt").asLong() == 0)).allSatisfy(p -> assertThat(p.has("limitCutPolicy")).isFalse());
    }

    /** C4: 즉시분 + Σ DeferralScheduled = 분할 전 총액, reason CURVE. 도래·해약 전이는 사유와 함께. */
    @Test
    void 분급_분할과_도래와_해약_취소() {
        OutboxScenario s = new OutboxScenario();
        LocalDate contract = LocalDate.of(2027, 1, 10);
        s.calculator.process(EventFixtures.newContract(POLICY_2, contract, Money.won(300_000), Map.of()));

        JsonNode fy = s.payloads(OutboxCommCalcStore.TYPE).stream()
                .filter(p -> p.path("commType").asText().equals("FY_COMM")).findFirst().orElseThrow();
        List<JsonNode> scheduled = s.payloads(OutboxDeferralScheduleStore.SCHEDULED);
        assertThat(scheduled).hasSize(3).allSatisfy(p -> {
            assertThat(p.path("reason").asText()).isEqualTo("CURVE");
            assertThat(p.path("sourceCalcId").asText()).isEqualTo(fy.path("calcId").asText());
        });
        CommCalcRecord rec = s.rawCalcs.findById(Long.parseLong(fy.path("calcId").asText())).orElseThrow();
        long total = Long.parseLong(PortalPayloads.traceValue(rec.calcTrace(), "DEFERRAL_SPLIT_V1", "total", null).orElseThrow());
        long deferred = Long.parseLong(PortalPayloads.traceValue(rec.calcTrace(), "DEFERRAL_SPLIT_V1", "deferred", null).orElseThrow());
        assertThat(sum(scheduled, "amount")).isEqualTo(deferred);
        assertThat(fy.path("amount").asLong() + sum(scheduled, "amount")).isEqualTo(total);

        // 첫 도래분(12개월 뒤) 지급 투입 → RELEASED + DEFERRED 계산
        String firstDue = scheduled.get(0).path("dueYm").asText();
        s.releaseService.release(CloseYm.of(firstDue));
        List<JsonNode> changed = s.payloads(OutboxDeferralScheduleStore.STATUS_CHANGED);
        assertThat(changed).hasSize(1);
        assertThat(changed.get(0).path("from").asText()).isEqualTo("SCHEDULED");
        assertThat(changed.get(0).path("to").asText()).isEqualTo("RELEASED");
        assertThat(changed.get(0).path("reason").asText()).isEqualTo(DeferralTransitionReason.DUE_RELEASED.name());
        assertThat(s.payloads(OutboxCommCalcStore.TYPE)).anyMatch(p -> p.path("commType").asText().equals("DEFERRED")
                && p.path("amount").asLong() == scheduled.get(0).path("amount").asLong());

        // 해약 → 남은 둘 CANCELLED(POLICY_TERMINATED) + PolicyEventObserved(CANCEL)
        s.calculator.process(EventFixtures.terminal(EventType.CANCEL, POLICY_2, contract, LocalDate.parse(firstDue.substring(0, 4)
                + "-" + firstDue.substring(4) + "-20"), 13));
        List<JsonNode> cancelled = s.payloads(OutboxDeferralScheduleStore.STATUS_CHANGED).stream()
                .filter(p -> p.path("to").asText().equals("CANCELLED")).toList();
        assertThat(cancelled).hasSize(2).allSatisfy(p -> {
            assertThat(p.path("from").asText()).isEqualTo("SCHEDULED");
            assertThat(p.path("reason").asText()).isEqualTo(DeferralTransitionReason.POLICY_TERMINATED.name());
        });
        assertThat(s.payloads(OutboxPolicyEventStore.TYPE)).extracting(p -> p.path("eventType").asText()).containsExactly("CANCEL");
    }

    @Test
    void 정정은_원본을_가리키는_음수_계산과_분급_취소_사유를_낸다() {
        OutboxScenario s = new OutboxScenario();
        LocalDate contract = LocalDate.of(2027, 1, 10);
        s.calculator.process(EventFixtures.newContract(POLICY_2, contract, Money.won(300_000), Map.of()));
        JsonNode fy = s.payloads(OutboxCommCalcStore.TYPE).get(0);

        s.revision.reverse(Long.parseLong(fy.path("calcId").asText()), "요율 정정");

        JsonNode reversal = s.payloads(OutboxCommCalcStore.TYPE).stream().filter(p -> p.has("reversalOfCalcId")).findFirst().orElseThrow();
        assertThat(reversal.path("reversalOfCalcId").asText()).isEqualTo(fy.path("calcId").asText());
        assertThat(reversal.path("amount").asLong()).isEqualTo(-fy.path("amount").asLong());
        assertThat(s.payloads(OutboxDeferralScheduleStore.STATUS_CHANGED)).hasSize(3).allSatisfy(p ->
                assertThat(p.path("reason").asText()).isEqualTo(DeferralTransitionReason.SOURCE_REVERSED.name()));
        // 원장: 원본 전기(분할 전 지급분 — 게이트가 분할보다 앞)를 되돌리는 음수 전기(LimitReversalHook), 순번이 이어진다
        List<JsonNode> posted = s.payloads(OutboxLimitLedgerStore.POSTED);
        assertThat(posted).extracting(p -> p.path("postingSeq").asInt()).containsExactly(1, 2);
        assertThat(posted.get(1).path("amount").asLong()).isEqualTo(-posted.get(0).path("amount").asLong());
        assertThat(posted.get(1).path("accumAfter").asLong()).isZero();
    }

    @Test
    void 감액은_정책_이벤트와_재산정을_낸다() {
        OutboxScenario s = new OutboxScenario();
        s.calculator.process(EventFixtures.newContract());
        s.calculator.process(EventFixtures.reduce(EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE, LocalDate.of(2026, 9, 10),
                Money.won(200_000)));

        assertThat(s.payloads(OutboxPolicyEventStore.TYPE)).extracting(p -> p.path("eventType").asText()).containsExactly("REDUCE");
        List<JsonNode> rebased = s.payloads(OutboxLimitLedgerStore.REBASED);
        assertThat(rebased).hasSize(1);
        assertThat(rebased.get(0).path("oldLimit").asLong()).isEqualTo(3_600_000);
        assertThat(rebased.get(0).path("newLimit").asLong()).isEqualTo(2_400_000);
        assertThat(rebased.get(0).path("monthlyPremium").asLong()).isEqualTo(200_000);
        assertThat(rebased.get(0).path("reason").asText()).isEqualTo("PREMIUM_REDUCED");
        assertThat(rebased.get(0).path("postingSeq").asInt()).isEqualTo(1);   // 재산정 직전 마지막 전기
    }

    @Test
    void 같은_키의_재수신은_정책_이벤트를_다시_내지_않는다() {
        OutboxScenario s = new OutboxScenario();
        var cancel = EventFixtures.terminal(EventType.LAPSE, EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE,
                LocalDate.of(2026, 9, 10), 2);
        s.stores.events().upsertByKey(cancel);
        s.stores.events().upsertByKey(cancel);
        assertThat(s.payloads(OutboxPolicyEventStore.TYPE)).hasSize(1);
    }

    /** C5·C6: 마감·지급 사이클 — 설계사별 명세, 집계 정의, 채권, ORG 제외. */
    @Test
    void 마감과_지급과_채권_사이클() {
        OutboxScenario s = new OutboxScenario();
        s.calculator.process(EventFixtures.newContract());
        s.closeService.close(CloseYm.of("202608"), "정산담당");
        s.payoutService.run(CloseYm.of("202608"), 1);

        JsonNode aug = s.payloads(StatementPublisher.TYPE).get(0);
        assertThat(s.events(StatementPublisher.TYPE)).hasSize(1);   // 설계사 1명 — ORG 명세는 없다
        assertThat(aug.path("agentId").asText()).isEqualTo("A-1001");
        assertThat(aug.path("gross").asLong()).isEqualTo(1_890_000);
        assertThat(aug.path("clawbackOffset").asLong()).isZero();
        assertThat(aug.path("adjustment").asLong()).isZero();
        assertThat(aug.path("closedAt").asText()).isEqualTo("2026-09-05T18:00:00+09:00");
        JsonNode augEnvelope = s.events(StatementPublisher.TYPE).get(0);
        assertThat(augEnvelope.path("occurredAt").asText()).isEqualTo(aug.path("closedAt").asText());
        assertThat(augEnvelope.path("aggregate").path("id").asText()).isEqualTo("A-1001:202608");

        List<JsonNode> paid = s.payloads(PayoutPublisher.TYPE);
        assertThat(paid).hasSize(1);   // ORG 수급자 정산은 발행하지 않는다
        assertThat(paid.get(0).path("net").asLong()).isEqualTo(1_827_630);
        assertThat(paid.get(0).path("withholdingTax").asLong()).isEqualTo(62_370);
        assertThat(paid.get(0).path("runSeq").asInt()).isEqualTo(1);
        assertThat(paid.get(0).path("paidAt").asText()).isEqualTo(s.statementStore.findByCloseYm(CloseYm.of("202608"))
                .stream().filter(x -> x.statement().recipientType() == RecipientType.AGENT).findFirst().orElseThrow()
                .paidAt().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        // 9월 해약 → 환수 → 순액 음수 → 채권화(ClawbackReceivableUpdated OPEN)
        s.calculator.process(EventFixtures.terminal(EventType.CANCEL, EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE,
                LocalDate.of(2026, 9, 15), 2));
        s.closeService.close(CloseYm.of("202609"), "정산담당");
        s.payoutService.run(CloseYm.of("202609"), 1);
        JsonNode sep = s.payloads(StatementPublisher.TYPE).get(1);
        assertThat(sep.path("gross").asLong()).isZero();
        assertThat(sep.path("clawbackOffset").asLong()).isEqualTo(-1_323_000);
        JsonNode sepPaid = s.payloads(PayoutPublisher.TYPE).get(1);
        assertThat(sepPaid.path("carriedReceivable").asLong()).isEqualTo(1_323_000);
        assertThat(sepPaid.path("gross").asLong() + sepPaid.path("clawbackOffset").asLong()).isEqualTo(-1_323_000);
        List<JsonNode> receivables = s.payloads(OutboxClawbackReceivableStore.TYPE);
        assertThat(receivables).hasSize(1);
        assertThat(receivables.get(0).path("status").asText()).isEqualTo("OPEN");
        assertThat(receivables.get(0).path("remaining").asLong()).isEqualTo(1_323_000);

        // 10월 다른 계약 → 지급 런에서 채권 상계(ClawbackReceivableUpdated CLOSED)
        s.calculator.process(EventFixtures.newContract(POLICY_2, LocalDate.of(2026, 10, 1), Money.won(300_000), Map.of()));
        s.closeService.close(CloseYm.of("202610"), "정산담당");
        s.payoutService.run(CloseYm.of("202610"), 1);
        List<JsonNode> receivables2 = s.payloads(OutboxClawbackReceivableStore.TYPE);
        assertThat(receivables2).hasSize(2);
        assertThat(receivables2.get(1).path("status").asText()).isEqualTo("CLOSED");
        assertThat(receivables2.get(1).path("remaining").asLong()).isZero();
        JsonNode octPaid = s.payloads(PayoutPublisher.TYPE).get(2);
        assertThat(octPaid.path("receivableOffset").asLong()).isEqualTo(1_323_000);
        assertThat(octPaid.path("payable").asLong()).isEqualTo(567_000);

        // C5: 각 지급 이벤트의 gross + clawbackOffset = 그 정산 행 gross_net
        for (JsonNode p : s.payloads(PayoutPublisher.TYPE)) {
            CloseYm ym = CloseYm.of(p.path("closeYm").asText());
            var row = s.settlementStore.findByCloseYm(ym).stream().filter(r -> r.recipientType() == RecipientType.AGENT).findFirst().orElseThrow();
            assertThat(p.path("gross").asLong() + p.path("clawbackOffset").asLong()).isEqualTo(row.grossNet().toLong());
        }
        // C6: 발행된 어떤 이벤트에도 ORG 식별자가 설계사로 나오지 않는다
        Set<String> agentIds = s.events().stream().map(e -> e.path("payload").path("agentId").asText(null))
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        assertThat(agentIds).containsExactly("A-1001").doesNotContainAnyElementsOf(ORG_IDS);
    }

    /** C5: 마감 전 정정(REVERSED 원본·reversal·rebook 공존)에서도 gross + clawbackOffset = gross_net(이중 차감 없음). */
    @Test
    void 마감_전_정정이_있어도_명세_집계는_순액과_같다() {
        OutboxScenario s = new OutboxScenario();
        var event = EventFixtures.newContract();
        s.calculator.process(event);
        var stored = s.rawEvents.findById(s.rawCalcs.all().get(0).eventId()).orElseThrow();
        s.revision.rebookEvent(stored, s.calculator, "요율 정정");
        s.closeService.close(CloseYm.of("202608"), "정산담당");
        s.payoutService.run(CloseYm.of("202608"), 1);

        List<CommCalcRecord> agentRecords = s.rawCalcs.findByCloseYm(CloseYm.of("202608")).stream()
                .filter(r -> r.recipientType() == RecipientType.AGENT).toList();
        assertThat(agentRecords).extracting(r -> r.status().name()).contains("REVERSED");
        JsonNode confirmed = s.payloads(StatementPublisher.TYPE).get(0);
        assertThat(confirmed.path("gross").asLong() + confirmed.path("clawbackOffset").asLong())
                .isEqualTo(NetAmountCalculator.netOf(agentRecords).toLong())
                .isEqualTo(1_890_000);
        JsonNode paid = s.payloads(PayoutPublisher.TYPE).get(0);
        assertThat(paid.path("gross").asLong()).isEqualTo(1_890_000);
    }

    @Test
    void 정정_조정은_대상의_설계사로_발행되고_다음_달_명세에_합산된다() {
        OutboxScenario s = new OutboxScenario();
        s.calculator.process(EventFixtures.newContract());
        s.closeService.close(CloseYm.of("202608"), "정산담당");
        long fyCalcId = Long.parseLong(s.payloads(OutboxCommCalcStore.TYPE).get(0).path("calcId").asText());
        long orgCalcId = s.rawCalcs.all().stream().filter(r -> r.recipientType() == RecipientType.ORG).findFirst().orElseThrow().calcId();

        s.adjustmentService.adjust(fyCalcId, "소급 요율 정정", Money.won(-45_000), "정산팀장");
        s.adjustmentService.adjust(orgCalcId, "조직 정정", Money.won(-1_000), "정산팀장");   // ORG 대상 — 발행 없음

        List<JsonNode> adjustments = s.payloads(OutboxAdjustmentStore.TYPE);
        assertThat(adjustments).hasSize(1);
        assertThat(adjustments.get(0).path("agentId").asText()).isEqualTo("A-1001");
        assertThat(adjustments.get(0).path("closeYm").asText()).isEqualTo("202609");   // 익월 귀속
        assertThat(adjustments.get(0).path("targetCalcId").asText()).isEqualTo(Long.toString(fyCalcId));

        s.closeService.close(CloseYm.of("202609"), "정산담당");   // 9월에는 계산이 없고 정정만 있다
        List<JsonNode> statements = s.payloads(StatementPublisher.TYPE);
        assertThat(statements).hasSize(2);
        assertThat(statements.get(1).path("closeYm").asText()).isEqualTo("202609");
        assertThat(statements.get(1).path("adjustment").asLong()).isEqualTo(-45_000);
        assertThat(statements.get(1).path("gross").asLong()).isZero();
    }

    @Test
    void 사유_없는_분급_전이와_데코레이터_밖에서_적재한_원장_저장은_거부된다() {
        OutboxScenario s = new OutboxScenario();
        s.calculator.process(EventFixtures.newContract(POLICY_2, LocalDate.of(2027, 1, 10), Money.won(300_000), Map.of()));
        ScheduleEntry entry = s.rawSchedules.all().get(0);
        assertThatThrownBy(() -> s.stores.schedules().replace(entry.cancelled()))
                .isInstanceOf(UnsupportedOperationException.class);

        LimitLedger foreign = new LimitLedger(99, POLICY_2, EventFixtures.AGENT_A, EventFixtures.CONTRACT_DATE,
                EventFixtures.CONTRACT_DATE, LocalDate.of(2027, 7, 31), Money.won(1), new java.math.BigDecimal("12"), 1);
        assertThatThrownBy(() -> s.stores.ledgers().save(foreign)).isInstanceOf(IllegalStateException.class);
    }
}
