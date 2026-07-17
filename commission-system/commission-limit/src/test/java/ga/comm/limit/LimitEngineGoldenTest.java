package ga.comm.limit;

import ga.comm.calc.StepConfig;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.limit.fixture.InMemoryLimitLedgerStore;
import ga.comm.rule.model.ChannelType;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.LimitRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 골든 케이스 (설계서 §10): 2026-06-30/07-01 경계, 한도 임박·초과·환수 후 복원.
 *
 * <p>표준 픽스처 기준 — 월납 300,000 → 한도 3,600,000.
 * 신계약 FY_COMM = 1,890,000(지급률 적용 후), 회차 FY_COMM = 40,500.
 */
class LimitEngineGoldenTest {

    private static final LocalDate GATE_ACTIVE = LocalDate.of(2026, 7, 1);

    private CalcTestHarness harness;
    private InMemoryLimitLedgerStore ledgers;

    @BeforeEach
    void setUp() {
        harness = new CalcTestHarness();
        ledgers = new InMemoryLimitLedgerStore();
        harness.addStep(new StepConfig(new LimitGateStep(ledgers), EffectivePeriod.from(GATE_ACTIVE), 50));
        harness.addStep(new StepConfig(new PremiumRepriceStep(ledgers), EffectivePeriod.from(GATE_ACTIVE), 60));
        harness.addListener(new LimitLedgerPoster(ledgers));
    }

    private LimitRule gaLimitRule() {
        return harness.rules.findLimitRule(ChannelType.GA_TO_AGENT, LocalDate.of(2026, 7, 1)).orElseThrow();
    }

    @Test
    @DisplayName("경계: 2026-06-30 체결 계약은 한도룰 미적용 — 원장도 생기지 않는다")
    void 경계_0630_체결은_미적용() {
        PolicyNo policy = new PolicyNo("POL-BOUNDARY-0630");
        LocalDate contract = LocalDate.of(2026, 6, 30);

        harness.calculator().process(EventFixtures.newContract(policy, contract,
                Money.won(300_000), Map.of("incentive_amount", "9000000")));
        // 규정 시행 후의 회차 입금이라도 "계약 체결일" 기준으로 미적용이어야 한다
        List<CommCalcRecord> paymentRecords = harness.calculator().process(
                EventFixtures.payment(policy, contract, 2, LocalDate.of(2026, 8, 5),
                        Money.won(300_000), Map.of("incentive_amount", "9000000")));

        assertThat(ledgers.findAll()).isEmpty();
        assertThat(paymentRecords).allSatisfy(r -> assertThat(r.limitCutAmt()).isEqualTo(Money.ZERO));
    }

    @Test
    @DisplayName("경계: 2026-07-01 체결 계약은 원장이 개설되고 한도=월납×12")
    void 경계_0701_체결은_적용() {
        PolicyNo policy = new PolicyNo("POL-BOUNDARY-0701");
        LocalDate contract = LocalDate.of(2026, 7, 1);

        harness.calculator().process(EventFixtures.newContract(policy, contract,
                Money.won(300_000), Map.of()));

        LimitLedger ledger = ledgers.find(policy, EventFixtures.AGENT_A).orElseThrow();
        assertThat(ledger.limitAmount()).isEqualTo(Money.won(3_600_000));
        assertThat(ledger.accumPaid()).isEqualTo(Money.won(1_890_000));
        assertThat(ledger.fyStart()).isEqualTo(contract);
        assertThat(ledger.fyEnd()).isEqualTo(LocalDate.of(2027, 6, 30));
    }

    @Test
    @DisplayName("골든(부록 A형): 한도 임박 시 시책이 잔여분만 지급되고 초과분은 이연 기록된다")
    void 한도_임박_초과_삭감() {
        // 신계약 + 시책 1,000,000: 누적 1,890,000 + 1,000,000 = 2,890,000
        harness.calculator().process(EventFixtures.newContract(EventFixtures.POLICY_1,
                EventFixtures.CONTRACT_DATE, Money.won(300_000),
                Map.of("incentive_amount", "1000000")));

        // 2회차: 회차수수료 40,500 → 누적 2,930,500. 시책 800,000 중 잔여 669,500만 지급
        List<CommCalcRecord> records = harness.calculator().process(
                EventFixtures.payment(EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE, 2,
                        LocalDate.of(2026, 9, 5), Money.won(300_000),
                        Map.of("incentive_amount", "800000")));

        CommCalcRecord incentive = records.stream()
                .filter(r -> r.commType().equals(CommTypeCode.INCENTIVE)).findFirst().orElseThrow();
        assertThat(incentive.calcAmount()).isEqualTo(Money.won(669_500));
        assertThat(incentive.limitCutAmt()).isEqualTo(Money.won(130_500));
        assertThat(incentive.calcTrace()).contains("DEFER_AFTER_FY");

        LimitLedger ledger = ledgers.find(EventFixtures.POLICY_1, EventFixtures.AGENT_A).orElseThrow();
        assertThat(ledger.accumPaid()).isEqualTo(ledger.limitAmount());
        assertThat(ledger.invariantHolds()).isTrue();
    }

    @Test
    @DisplayName("한도 소진 후 시책은 전액 이연되고 0원 지급 레코드로 근거가 남는다")
    void 한도_소진_후_전액_이연() {
        한도_임박_초과_삭감();

        List<CommCalcRecord> records = harness.calculator().process(
                EventFixtures.payment(EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE, 3,
                        LocalDate.of(2026, 10, 5), Money.won(300_000),
                        Map.of("incentive_amount", "500000")));

        // 회차수수료(FY_COMM 40,500)도 한도 포함이므로 전액 이연
        assertThat(records.stream().filter(r -> r.recipientId().equals(EventFixtures.AGENT_A.value())))
                .allSatisfy(r -> {
                    assertThat(r.calcAmount()).isEqualTo(Money.ZERO);
                    assertThat(r.limitCutAmt().isPositive()).isTrue();
                });

        LimitLedger ledger = ledgers.find(EventFixtures.POLICY_1, EventFixtures.AGENT_A).orElseThrow();
        assertThat(ledger.accumPaid()).isEqualTo(ledger.limitAmount());
    }

    @Test
    @DisplayName("환수 후 복원: 원장 차감만큼 한도 여유가 다시 생긴다")
    void 환수_후_복원() {
        한도_임박_초과_삭감();

        LimitLedgerService service = new LimitLedgerService(ledgers);
        boolean deducted = service.deductForClawback(EventFixtures.POLICY_1, EventFixtures.AGENT_A,
                LocalDate.of(2026, 10, 1), gaLimitRule(), 99_001L, Money.won(600_000));
        assertThat(deducted).isTrue();

        LimitLedger ledger = ledgers.find(EventFixtures.POLICY_1, EventFixtures.AGENT_A).orElseThrow();
        assertThat(ledger.limitAmount().minus(ledger.accumPaid())).isEqualTo(Money.won(600_000));

        // 복원된 여유 내에서 새 시책 지급 가능
        List<CommCalcRecord> records = harness.calculator().process(
                EventFixtures.payment(EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE, 4,
                        LocalDate.of(2026, 11, 5), Money.won(300_000),
                        Map.of("incentive_amount", "400000")));
        CommCalcRecord incentive = records.stream()
                .filter(r -> r.commType().equals(CommTypeCode.INCENTIVE)).findFirst().orElseThrow();
        assertThat(incentive.calcAmount()).isEqualTo(Money.won(400_000));
        assertThat(incentive.limitCutAmt()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("감액 재산정: 신한도가 기지급 아래로 내려가면 향후 지급이 차단된다")
    void 감액_재산정_후_지급_차단() {
        harness.calculator().process(EventFixtures.newContract());  // 누적 1,890,000 / 한도 3,600,000

        harness.calculator().process(EventFixtures.reduce(EventFixtures.POLICY_1,
                EventFixtures.CONTRACT_DATE, LocalDate.of(2026, 9, 1), Money.won(100_000)));

        LimitLedger ledger = ledgers.find(EventFixtures.POLICY_1, EventFixtures.AGENT_A).orElseThrow();
        assertThat(ledger.limitAmount()).isEqualTo(Money.won(1_200_000));
        assertThat(ledger.accumPaid()).isEqualTo(Money.won(1_890_000));  // 기지급 유지
        assertThat(ledger.available()).isEqualTo(Money.ZERO);

        List<CommCalcRecord> records = harness.calculator().process(
                EventFixtures.payment(EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE, 2,
                        LocalDate.of(2026, 10, 5), Money.won(100_000),
                        Map.of("incentive_amount", "300000")));
        assertThat(records.stream().filter(r -> r.recipientId().equals(EventFixtures.AGENT_A.value())))
                .allSatisfy(r -> assertThat(r.calcAmount()).isEqualTo(Money.ZERO));
    }

    @Test
    @DisplayName("초년도 윈도우 밖 이벤트는 게이트를 타지 않는다")
    void 초년도_윈도우_밖은_미적용() {
        harness.calculator().process(EventFixtures.newContract());

        // fyEnd = 2027-07-31, 이후 시책은 게이트 미적용 (원장 전기도 없음)
        List<CommCalcRecord> records = harness.calculator().process(
                EventFixtures.payment(EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE, 13,
                        LocalDate.of(2027, 8, 5), Money.won(300_000),
                        Map.of("incentive_amount", "5000000")));

        CommCalcRecord incentive = records.stream()
                .filter(r -> r.commType().equals(CommTypeCode.INCENTIVE)).findFirst().orElseThrow();
        assertThat(incentive.calcAmount()).isEqualTo(Money.won(5_000_000));

        LimitLedger ledger = ledgers.find(EventFixtures.POLICY_1, EventFixtures.AGENT_A).orElseThrow();
        assertThat(ledger.accumPaid()).isEqualTo(Money.won(1_890_000));  // 신계약분만
    }
}
