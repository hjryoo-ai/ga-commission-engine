package ga.comm.outbox.publish;

import com.fasterxml.jackson.databind.node.ObjectNode;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.clawback.ClawbackReceivable;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;
import ga.comm.domain.type.EventType;
import ga.comm.domain.type.RecipientType;
import ga.comm.outbox.contract.PortalContract;
import ga.comm.settlement.AdjustmentService;
import ga.comm.settlement.PayoutService;
import ga.comm.settlement.SettlementRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 빌더 단위 시험(Phase E1 C4·C5·D-7) — 매핑 표, 집계 정의, 계약에 담을 수 없는 값의 fail-fast. 결과는 포털 payload 스키마로도 검증한다. */
class PortalPayloadsTest {

    private static final CloseYm YM = CloseYm.of("202609");
    private static final String TRACE = "[{\"step\":\"PAYOUT_RATE_V1\",\"msg\":\"지급률 적용\",\"values\":{\"commType\":\"OVERRIDE\",\"payoutRate\":\"0.5\"}},"
            + "{\"step\":\"PAYOUT_RATE_V1\",\"msg\":\"지급률 적용\",\"values\":{\"commType\":\"FY_COMM\",\"payoutRate\":\"0.90\"}},"
            + "{\"step\":\"LIMIT_GATE_V1\",\"msg\":\"한도 초과\",\"values\":{\"commType\":\"INCENTIVE\",\"action\":\"CUT\"}},"
            + "{\"step\":\"LIMIT_GATE_V1\",\"msg\":\"한도 초과\",\"values\":{\"commType\":\"FY_COMM\",\"action\":\"DEFER_AFTER_FY\"}}]";
    private static final String RULES = "[{\"type\":\"COMM_RATE\",\"key\":\"FY_COMM\",\"ref\":\"rateId=1001\"},"
            + "{\"type\":\"LIMIT\",\"key\":\"GA_TO_AGENT\",\"ref\":\"ruleId=1\"}]";

    private static CommCalcRecord record(long id, RecipientType type, String recipient, String commType, long amount, long cut,
                                         CalcStatus status, Long reversalOf) {
        return new CommCalcRecord(id, 7L, EventFixtures.POLICY_1, type, recipient, new CommTypeCode(commType),
                Money.won(2_100_000), Rate.of("7.00"), Money.won(amount), Money.won(cut), YM, status, reversalOf, RULES, TRACE);
    }

    private static PolicyEvent event(EventType type, Integer installment) {
        return new PolicyEvent(7L, "K", EventFixtures.POLICY_1, ga.comm.rule.fixture.RuleFixtures.INSURER,
                ga.comm.rule.fixture.RuleFixtures.PRODUCT, type, LocalDate.of(2026, 9, 5), EventFixtures.CONTRACT_DATE,
                EventFixtures.AGENT_A, Money.won(300_000), null, installment, Map.of());
    }

    private static void assertValid(String type, ObjectNode payload) {
        assertThat(PortalContract.get().validatePayload(type, payload)).as(type + " " + payload).isEmpty();
    }

    @Test
    void 계산_이벤트는_엔진_값을_그대로_옮긴다() {
        ObjectNode p = PortalPayloads.commissionCalculated(
                record(11, RecipientType.AGENT, "A-1001", "FY_COMM", 1_000_000, 890_000, CalcStatus.CALCULATED, null),
                event(EventType.PAYMENT, 2));

        assertValid("CommissionCalculated", p);
        assertThat(p.path("amount").asLong()).isEqualTo(1_000_000);
        assertThat(p.path("limitCutAmt").asLong()).isEqualTo(890_000);
        assertThat(p.path("limitCutPolicy").asText()).isEqualTo("DEFER_AFTER_FY");   // 그 유형의 게이트 항목(INCENTIVE의 CUT가 아니다)
        assertThat(p.path("basis").path("rate").asText()).isEqualTo("7");
        assertThat(p.path("basis").path("payoutRate").asText()).isEqualTo("0.9");    // 그 유형의 지급률(OVERRIDE 0.5가 아니다)
        assertThat(p.path("basis").path("installmentNo").asInt()).isEqualTo(2);
        assertThat(p.path("basis").path("ruleVersionIds")).extracting(n -> n.asText())
                .containsExactly("COMM_RATE:FY_COMM:rateId=1001", "LIMIT:GA_TO_AGENT:ruleId=1");
        assertThat(p.path("policySummary").path("contractDate").asText()).isEqualTo("2026-08-01");
        assertThat(p.has("reversalOfCalcId")).isFalse();
    }

    @Test
    void 삭감이_없으면_정책을_싣지_않고_정정이면_원본을_싣는다() {
        ObjectNode p = PortalPayloads.commissionCalculated(
                record(12, RecipientType.AGENT, "A-1001", "FY_COMM", -1_000_000, 0, CalcStatus.CALCULATED, 11L),
                event(EventType.NEW, null));

        assertValid("CommissionCalculated", p);
        assertThat(p.has("limitCutPolicy")).isFalse();
        assertThat(p.path("basis").has("installmentNo")).isFalse();
        assertThat(p.path("reversalOfCalcId").asText()).isEqualTo("11");
    }

    @Test
    void ORG_수급자는_빌더가_거부한다() {
        assertThatThrownBy(() -> PortalPayloads.commissionCalculated(
                record(13, RecipientType.ORG, "T1", "OVERRIDE", 105_000, 0, CalcStatus.CALCULATED, null),
                event(EventType.NEW, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ORG");
    }

    @Test
    void 정책_이벤트_매핑_표() {
        Map<EventType, String> expected = Map.of(EventType.CANCEL, "CANCEL", EventType.LAPSE, "LAPSE",
                EventType.WITHDRAW, "WITHDRAW", EventType.REDUCE, "REDUCE", EventType.REVIVE, "REINSTATE");
        assertThat(PortalPayloads.POLICY_EVENT_TYPES).isEqualTo(expected);
        for (EventType t : EventType.values()) {
            var mapped = PortalPayloads.policyEventObserved(event(t, null));
            if (expected.containsKey(t)) {
                assertThat(mapped).as(t.name()).isPresent();
                assertThat(mapped.get().path("eventType").asText()).isEqualTo(expected.get(t));
                assertThat(mapped.get().path("occurredOn").asText()).isEqualTo("2026-09-05");
                assertValid("PolicyEventObserved", mapped.get());
            } else {
                assertThat(mapped).as(t.name()).isEmpty();
            }
        }
        // 엔진 어휘가 늘면 이 표를 다시 보라는 경보 — 매핑되지 않는 유형은 지금 셋뿐이다
        assertThat(EnumSet.complementOf(EnumSet.copyOf(expected.keySet())))
                .containsExactlyInAnyOrder(EventType.NEW, EventType.PAYMENT, EventType.CORRECT);
    }

    @ParameterizedTest
    @EnumSource(PortalPayloads.RebaseReason.class)
    void 재산정_사유는_한도의_방향이다(PortalPayloads.RebaseReason reason) {
        ga.comm.limit.LimitLedger l = new ga.comm.limit.LimitLedger(5, EventFixtures.POLICY_1, EventFixtures.AGENT_A,
                EventFixtures.CONTRACT_DATE, EventFixtures.CONTRACT_DATE, LocalDate.of(2027, 7, 31), Money.won(300_000),
                new BigDecimal("12.00"), 1);
        Money old = l.limitAmount();
        l.reprice(Money.won(reason == PortalPayloads.RebaseReason.PREMIUM_REDUCED ? 200_000 : 400_000));
        ObjectNode withSeq = PortalPayloads.limitLedgerRebased(l, old, 3);
        ObjectNode noSeq = PortalPayloads.limitLedgerRebased(l, old, 0);

        assertValid("LimitLedgerRebased", withSeq);
        assertValid("LimitLedgerRebased", noSeq);
        assertThat(withSeq.path("reason").asText()).isEqualTo(reason.name());
        assertThat(withSeq.path("postingSeq").asInt()).isEqualTo(3);
        assertThat(noSeq.has("postingSeq")).isFalse();   // 전기 없는 원장(계약 v1.1.1 L1)
    }

    /** C5: REVERSED 원본·reversal·rebook·환수가 공존해도 gross + clawbackOffset = 전 레코드 순합(상태 필터 없음). */
    @Test
    void 집계는_상태와_무관하게_유형으로만_나눈다() {
        List<CommCalcRecord> records = List.of(
                record(1, RecipientType.AGENT, "A-1001", "FY_COMM", 1_890_000, 0, CalcStatus.REVERSED, null),
                record(2, RecipientType.AGENT, "A-1001", "FY_COMM", -1_890_000, 0, CalcStatus.CONFIRMED, 1L),
                record(3, RecipientType.AGENT, "A-1001", "FY_COMM", 1_701_000, 0, CalcStatus.CONFIRMED, null),
                record(4, RecipientType.AGENT, "A-1001", "CLAWBACK", -500_000, 0, CalcStatus.CONFIRMED, null));

        PortalPayloads.Totals t = PortalPayloads.totals(records);

        assertThat(t.gross()).isEqualTo(Money.won(1_701_000));
        assertThat(t.clawbackOffset()).isEqualTo(Money.won(-500_000));
        assertThat(t.net()).isEqualTo(ga.comm.calc.net.NetAmountCalculator.netOf(records));

        ObjectNode confirmed = PortalPayloads.statementConfirmed("A-1001", YM, records, Money.won(-45_000),
                OffsetDateTime.parse("2026-10-05T18:00:00+09:00"));
        assertValid("StatementConfirmed", confirmed);
        assertThat(confirmed.path("gross").asLong()).isEqualTo(1_701_000);
        assertThat(confirmed.path("clawbackOffset").asLong()).isEqualTo(-500_000);
        assertThat(confirmed.path("adjustment").asLong()).isEqualTo(-45_000);
        assertThat(confirmed.has("net")).isFalse();
        assertThat(confirmed.has("withholdingTax")).isFalse();

        SettlementRow row = new SettlementRow(YM, 1, RecipientType.AGENT, "A-1001", Money.won(1_201_000), Money.ZERO, Money.ZERO,
                Money.won(1_201_000), List.of(1L, 2L, 3L, 4L));
        PayoutService.PayoutStatement s = new PayoutService.PayoutStatement(YM, 1, RecipientType.AGENT, "A-1001",
                Money.won(1_201_000), Money.won(36_030), Money.won(3_603), Money.won(1_161_367));
        ObjectNode paid = PortalPayloads.payoutCompleted(s, row, records, OffsetDateTime.parse("2026-10-06T10:00:00+09:00"));
        assertValid("PayoutCompleted", paid);
        assertThat(paid.path("gross").asLong() + paid.path("clawbackOffset").asLong()).isEqualTo(row.grossNet().toLong());
        assertThat(paid.path("withholdingTax").asLong()).isEqualTo(39_633);
        assertThat(paid.path("net").asLong()).isEqualTo(1_161_367);
        assertThat(paid.path("paidAmount").asLong()).isEqualTo(1_161_367);
        assertThat(paid.has("adjustment")).isFalse();   // E1 P5 — 정정은 지급 순액에 없다
    }

    @Test
    void 지급_합산_정의가_어긋나면_발행하지_않는다() {
        List<CommCalcRecord> records = List.of(record(1, RecipientType.AGENT, "A-1001", "FY_COMM", 1_890_000, 0, CalcStatus.CONFIRMED, null));
        SettlementRow row = new SettlementRow(YM, 1, RecipientType.AGENT, "A-1001", Money.won(1_000_000), Money.ZERO, Money.ZERO,
                Money.won(1_000_000), List.of(1L));
        PayoutService.PayoutStatement s = new PayoutService.PayoutStatement(YM, 1, RecipientType.AGENT, "A-1001",
                Money.won(1_000_000), Money.ZERO, Money.ZERO, Money.won(1_000_000));

        assertThatThrownBy(() -> PortalPayloads.payoutCompleted(s, row, records, OffsetDateTime.parse("2026-10-06T10:00:00+09:00")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("gross_net");
    }

    @Test
    void 계약이_요구하는_원인이_없으면_실패한다() {
        assertThatThrownBy(() -> PortalPayloads.clawbackReceivableUpdated(new ClawbackReceivable(1, new AgentId("A-1001"), null,
                Money.won(100))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("originCalcId");
        assertThatThrownBy(() -> PortalPayloads.adjustmentPosted(
                new AdjustmentService.Adjustment(1, null, "수기", Money.won(1), YM, "팀장"), "A-1001"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("targetCalcId");
    }

    @Test
    void 소수는_반올림하지_않고_계약_형식만_낸다() {
        assertThat(Decimals.plain(new BigDecimal("12.00"))).isEqualTo("12");
        assertThat(Decimals.plain(new BigDecimal("0.150000"))).isEqualTo("0.15");
        assertThat(Decimals.plain(new BigDecimal("1E+1"))).isEqualTo("10");
        assertThatThrownBy(() -> Decimals.plain(new BigDecimal("0.1234567"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Decimals.plain(new BigDecimal("-0.5"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 룰_식별자가_계약_길이를_넘으면_실패한다() {
        String longRef = "x".repeat(60);
        assertThatThrownBy(() -> PortalPayloads.ruleVersionIds("[{\"type\":\"COMM_RATE\",\"key\":\"FY\",\"ref\":\"" + longRef + "\"}]"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("64");
        assertThat(PortalPayloads.ruleVersionIds(null)).isEmpty();
    }

    @Test
    void 정책_번호와_계약일은_이벤트에서() {
        ObjectNode p = PortalPayloads.commissionCalculated(
                new CommCalcRecord(21L, 7L, new PolicyNo("POL-X"), RecipientType.AGENT, "A-1001", CommTypeCode.DEFERRED,
                        Money.won(378_000), null, Money.won(378_000), Money.ZERO, YM, CalcStatus.CALCULATED, null,
                        "[{\"type\":\"DEFERRAL_CURVE\",\"key\":\"curveId=1\",\"ref\":\"scheduleId=3\"}]", "[]"),
                event(EventType.NEW, null));
        assertValid("CommissionCalculated", p);
        assertThat(p.path("basis").has("rate")).isFalse();   // 분급 도래 라인에는 요율이 없다(v1.1 — 있을 때만)
        assertThat(p.path("basis").has("payoutRate")).isFalse();
    }
}
