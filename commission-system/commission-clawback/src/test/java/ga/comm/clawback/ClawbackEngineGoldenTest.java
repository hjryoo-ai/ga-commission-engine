package ga.comm.clawback;

import ga.comm.calc.StepConfig;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.type.EventType;
import ga.comm.limit.LimitGateStep;
import ga.comm.limit.LimitLedger;
import ga.comm.limit.LimitLedgerPoster;
import ga.comm.limit.LimitLedgerService;
import ga.comm.limit.fixture.InMemoryLimitLedgerStore;
import ga.comm.rule.model.EffectivePeriod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 골든 케이스 (설계서 §10): 13회차 전후 해약·철회·부활.
 *
 * <p>표준 픽스처 기준 —
 * 신계약 1,890,000 + 2~7회차 각 40,500 → 환수대상 순기지급 2,133,000.
 * 환수율: 0~6회차 70%, 7~12회차 40%, 13회차~ 없음. 철회 100%.
 */
class ClawbackEngineGoldenTest {

    private CalcTestHarness harness;
    private InMemoryLimitLedgerStore ledgers;

    @BeforeEach
    void setUp() {
        harness = new CalcTestHarness();
        ledgers = new InMemoryLimitLedgerStore();
        LocalDate from = LocalDate.of(2026, 1, 1);
        harness.addStep(new StepConfig(new ClawbackStep(harness.calcStore), EffectivePeriod.from(from), 45));
        harness.addStep(new StepConfig(new ReviveStep(harness.calcStore, ReviveStep.RevivePolicy.REPAY),
                EffectivePeriod.from(from), 46));
        harness.addStep(new StepConfig(new LimitGateStep(ledgers),
                EffectivePeriod.from(LocalDate.of(2026, 7, 1)), 50));
        harness.addListener(new LimitLedgerPoster(ledgers));
        harness.addListener(new ClawbackPostProcessor(new LimitLedgerService(ledgers)));
    }

    /** 신계약 + 2~7회차 입금 → 순기지급 2,133,000, 원장 누적 2,133,000. */
    private void buildPaidHistory(PolicyNo policy) {
        harness.calculator().process(EventFixtures.newContract(policy,
                EventFixtures.CONTRACT_DATE, Money.won(300_000), Map.of()));
        for (int inst = 2; inst <= 7; inst++) {
            harness.calculator().process(EventFixtures.payment(policy, EventFixtures.CONTRACT_DATE,
                    inst, EventFixtures.CONTRACT_DATE.plusMonths(inst - 1), Money.won(300_000), Map.of()));
        }
    }

    private Optional<CommCalcRecord> clawbackRecordOf(List<CommCalcRecord> records) {
        return records.stream().filter(r -> r.commType().equals(CommTypeCode.CLAWBACK)).findFirst();
    }

    @Test
    @DisplayName("골든: 7회차 경과 해약 — 40% 환수, 한도 원장 차감")
    void 해약_7회차_환수() {
        buildPaidHistory(EventFixtures.POLICY_1);

        List<CommCalcRecord> records = harness.calculator().process(EventFixtures.terminal(
                EventType.CANCEL, EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE,
                LocalDate.of(2027, 2, 15), 7));

        CommCalcRecord clawback = clawbackRecordOf(records).orElseThrow();
        // 2,133,000 × 0.4 = 853,200 → -853,200
        assertThat(clawback.calcAmount()).isEqualTo(Money.won(-853_200));
        assertThat(clawback.baseAmount()).isEqualTo(Money.won(2_133_000));

        LimitLedger ledger = ledgers.find(EventFixtures.POLICY_1, EventFixtures.AGENT_A).orElseThrow();
        assertThat(ledger.accumPaid()).isEqualTo(Money.won(2_133_000 - 853_200));
        assertThat(ledger.invariantHolds()).isTrue();
    }

    @Test
    @DisplayName("골든: 13회차 경과 해약 — 환수 구간 밖, 환수 없음")
    void 해약_13회차_환수_없음() {
        buildPaidHistory(EventFixtures.POLICY_1);

        List<CommCalcRecord> records = harness.calculator().process(EventFixtures.terminal(
                EventType.CANCEL, EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE,
                LocalDate.of(2027, 9, 15), 13));

        assertThat(clawbackRecordOf(records)).isEmpty();
    }

    @Test
    @DisplayName("골든: 5회차 경과 실효 — 70% 환수")
    void 실효_5회차_환수() {
        buildPaidHistory(EventFixtures.POLICY_1);

        List<CommCalcRecord> records = harness.calculator().process(EventFixtures.terminal(
                EventType.LAPSE, EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE,
                LocalDate.of(2026, 12, 20), 5));

        // 2,133,000 × 0.7 = 1,493,100
        assertThat(clawbackRecordOf(records).orElseThrow().calcAmount())
                .isEqualTo(Money.won(-1_493_100));
    }

    @Test
    @DisplayName("골든: 청약철회 — 기지급 전액 환수, 원장 누적 0")
    void 청약철회_전액_환수() {
        buildPaidHistory(EventFixtures.POLICY_1);

        List<CommCalcRecord> records = harness.calculator().process(EventFixtures.terminal(
                EventType.WITHDRAW, EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE,
                LocalDate.of(2026, 9, 10), 2));

        assertThat(clawbackRecordOf(records).orElseThrow().calcAmount())
                .isEqualTo(Money.won(-2_133_000));
        LimitLedger ledger = ledgers.find(EventFixtures.POLICY_1, EventFixtures.AGENT_A).orElseThrow();
        assertThat(ledger.accumPaid()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("골든: 부활(REPAY 정책) — 환수 잔액 재지급, 원장 재가산")
    void 부활_재지급() {
        해약_7회차_환수();

        List<CommCalcRecord> records = harness.calculator().process(EventFixtures.terminal(
                EventType.REVIVE, EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE,
                LocalDate.of(2027, 4, 10), null));

        CommCalcRecord repay = clawbackRecordOf(records).orElseThrow();
        assertThat(repay.calcAmount()).isEqualTo(Money.won(853_200));

        LimitLedger ledger = ledgers.find(EventFixtures.POLICY_1, EventFixtures.AGENT_A).orElseThrow();
        assertThat(ledger.accumPaid()).isEqualTo(Money.won(2_133_000));
    }

    @Test
    @DisplayName("중복 환수 방지: 해약 환수 후 같은 계약의 재환수 기준액은 순잔액이다")
    void 중복_환수_방지() {
        해약_7회차_환수();

        // (가정) 정정으로 같은 계약에 또 CANCEL성 환수가 발생해도 기준은 순잔액
        List<CommCalcRecord> records = harness.calculator().process(EventFixtures.terminal(
                EventType.LAPSE, EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE,
                LocalDate.of(2027, 3, 15), 8));

        // 순잔액 = 2,133,000 − 853,200 = 1,279,800 → 40% = 511,920
        assertThat(clawbackRecordOf(records).orElseThrow().calcAmount())
                .isEqualTo(Money.won(-511_920));
    }
}
