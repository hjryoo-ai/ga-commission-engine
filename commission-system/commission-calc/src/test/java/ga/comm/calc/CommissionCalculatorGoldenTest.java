package ga.comm.calc;

import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Money;
import ga.comm.domain.type.CalcStatus;
import ga.comm.domain.type.RecipientType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 골든 케이스 (설계서 §10): 신계약/회차입금.
 *
 * <p>픽스처 기준 기대값 —
 * 신계약(월납 300,000): FY_COMM 성립 요율 7.0 → 2,100,000 × 지급률 0.9 = 1,890,000.
 * 오버라이드: 원수수료 2,100,000 기준 팀 5%=105,000, 지점 3%=63,000, 본부 2%=42,000.
 * 5회차 입금(300,000): 요율 0.15 → 45,000 × 0.9 = 40,500.
 * 13회차 입금: RENEWAL 0.02 → 6,000 × 0.9 = 5,400.
 */
class CommissionCalculatorGoldenTest {

    private CalcTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new CalcTestHarness();
    }

    @Test
    @DisplayName("골든: 신계약 체결 — 설계사 본인 + 조직 오버라이드 3건")
    void 신계약_골든_케이스() {
        List<CommCalcRecord> records = harness.calculator().process(EventFixtures.newContract());

        assertThat(records).hasSize(4);

        CommCalcRecord agentLine = records.stream()
                .filter(r -> r.recipientType() == RecipientType.AGENT).findFirst().orElseThrow();
        assertThat(agentLine.commType()).isEqualTo(CommTypeCode.FY_COMM);
        assertThat(agentLine.baseAmount()).isEqualTo(Money.won(300_000));
        assertThat(agentLine.calcAmount()).isEqualTo(Money.won(1_890_000));
        assertThat(agentLine.status()).isEqualTo(CalcStatus.CALCULATED);
        assertThat(agentLine.closeYm().value()).isEqualTo("202608");

        List<CommCalcRecord> overrides = records.stream()
                .filter(r -> r.recipientType() == RecipientType.ORG).toList();
        assertThat(overrides).extracting(CommCalcRecord::recipientId)
                .containsExactlyInAnyOrder("T1", "B1", "H1");
        assertThat(overrides).extracting(r -> r.calcAmount().toLong())
                .containsExactlyInAnyOrder(105_000L, 63_000L, 42_000L);
        assertThat(overrides).allSatisfy(r ->
                assertThat(r.commType()).isEqualTo(CommTypeCode.OVERRIDE));
    }

    @Test
    @DisplayName("골든: 5회차 입금 — 초년도 회차 요율")
    void 회차입금_초년도_골든_케이스() {
        List<CommCalcRecord> records = harness.calculator()
                .process(EventFixtures.payment(5, LocalDate.of(2026, 12, 5)));

        CommCalcRecord agentLine = records.stream()
                .filter(r -> r.recipientType() == RecipientType.AGENT).findFirst().orElseThrow();
        assertThat(agentLine.commType()).isEqualTo(CommTypeCode.FY_COMM);
        assertThat(agentLine.calcAmount()).isEqualTo(Money.won(40_500));
        assertThat(agentLine.closeYm().value()).isEqualTo("202612");
    }

    @Test
    @DisplayName("골든: 13회차 입금 — 계속수수료로 폴백")
    void 회차입금_계속수수료_골든_케이스() {
        List<CommCalcRecord> records = harness.calculator()
                .process(EventFixtures.payment(13, LocalDate.of(2027, 9, 5)));

        CommCalcRecord agentLine = records.stream()
                .filter(r -> r.recipientType() == RecipientType.AGENT).findFirst().orElseThrow();
        assertThat(agentLine.commType()).isEqualTo(CommTypeCode.RENEWAL);
        assertThat(agentLine.calcAmount()).isEqualTo(Money.won(5_400));
    }

    @Test
    @DisplayName("시책: 이벤트 속성으로 전달된 시책 금액이 INCENTIVE 라인으로 편입된다")
    void 시책_라인_편입() {
        List<CommCalcRecord> records = harness.calculator().process(
                EventFixtures.newContract(EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE,
                        EventFixtures.MONTHLY_PREMIUM, Map.of("incentive_amount", "1000000")));

        List<CommCalcRecord> incentives = records.stream()
                .filter(r -> r.commType().equals(CommTypeCode.INCENTIVE)).toList();
        assertThat(incentives).hasSize(1);
        assertThat(incentives.get(0).calcAmount()).isEqualTo(Money.won(1_000_000));
        assertThat(incentives.get(0).recipientType()).isEqualTo(RecipientType.AGENT);
    }

    @Test
    @DisplayName("멱등: 같은 event_key 재수신은 재계산하지 않는다")
    void 중복_이벤트는_멱등_처리된다() {
        CommissionCalculator calculator = harness.calculator();
        List<CommCalcRecord> first = calculator.process(EventFixtures.newContract());
        List<CommCalcRecord> second = calculator.process(EventFixtures.newContract());

        assertThat(harness.calcStore.all()).hasSize(first.size());
        assertThat(second).extracting(CommCalcRecord::calcId)
                .containsExactlyInAnyOrderElementsOf(first.stream().map(CommCalcRecord::calcId).toList());
    }

    @Test
    @DisplayName("근거 박제: rule_versions와 calc_trace가 저장된다")
    void 룰버전과_trace가_박제된다() {
        List<CommCalcRecord> records = harness.calculator().process(EventFixtures.newContract());

        CommCalcRecord agentLine = records.stream()
                .filter(r -> r.recipientType() == RecipientType.AGENT).findFirst().orElseThrow();
        assertThat(agentLine.ruleVersions())
                .contains("COMM_RATE")
                .contains("AGENT_PAYOUT_RATE")
                .contains("COMM_TYPE_MST");
        assertThat(agentLine.calcTrace())
                .contains("BASE_COMM_V1")
                .contains("PAYOUT_RATE_V1");
    }

    @Test
    @DisplayName("불변 원장: 상태 전이 외 UPDATE 경로가 없고, 허용되지 않는 전이는 거부된다")
    void 불변_원장_상태전이_검증() {
        List<CommCalcRecord> records = harness.calculator().process(EventFixtures.newContract());
        long calcId = records.get(0).calcId();

        harness.calcStore.transition(calcId, CalcStatus.CONFIRMED);
        harness.calcStore.transition(calcId, CalcStatus.PAID);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        harness.calcStore.transition(calcId, CalcStatus.CALCULATED))
                .isInstanceOf(IllegalStateException.class);
    }
}
