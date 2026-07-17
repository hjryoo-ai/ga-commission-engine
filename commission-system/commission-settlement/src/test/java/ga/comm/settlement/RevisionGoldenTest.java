package ga.comm.settlement;

import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.StepConfig;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.calc.revision.ReplayService;
import ga.comm.calc.revision.RevisionService;
import ga.comm.calc.store.CloseStatusProvider;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;
import ga.comm.domain.type.Direction;
import ga.comm.domain.type.RecipientType;
import ga.comm.limit.LimitGateStep;
import ga.comm.limit.LimitLedger;
import ga.comm.limit.LimitLedgerPoster;
import ga.comm.limit.LimitReversalHook;
import ga.comm.limit.fixture.InMemoryLimitLedgerStore;
import ga.comm.rule.admin.RateApprovalService;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.rule.model.EffectivePeriod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5 골든 케이스 (설계서 §10): 소급 요율 변경 시나리오 전체.
 *
 * <p>기대값 — 원 요율 7.0: FY 1,890,000. 소급 요율 6.5: 300,000×6.5×0.9 = 1,755,000.
 * 오버라이드: 2,100,000×5% = 105,000 → 1,950,000×5% = 97,500.
 */
class RevisionGoldenTest {

    private CalcTestHarness harness;
    private InMemoryLimitLedgerStore ledgers;
    private RevisionService revision;
    private CloseStatusProvider closeStatus;

    @BeforeEach
    void setUp() {
        harness = new CalcTestHarness();
        ledgers = new InMemoryLimitLedgerStore();
        harness.addStep(new StepConfig(new LimitGateStep(ledgers),
                EffectivePeriod.from(LocalDate.of(2026, 7, 1)), 50));
        harness.addListener(new LimitLedgerPoster(ledgers));
        closeStatus = CloseStatusProvider.noneClosed();
        revision = new RevisionService(harness.calcStore, closeStatus,
                List.of(new LimitReversalHook(ledgers)));
    }

    /** 신계약 처리 후 (저장된 이벤트, 계산기) 반환. */
    private PolicyEvent processNewContract(CommissionCalculator calculator) {
        List<CommCalcRecord> records = calculator.process(EventFixtures.newContract());
        return EventFixtures.newContract().withEventId(records.get(0).eventId());
    }

    /** 소급 요율 변경: 2026-08-01부터 성립 요율 7.0 → 6.5. */
    private void approveRetroactiveRate() {
        RateApprovalService approval = new RateApprovalService(harness.rules);
        approval.approve(approval.registerDraft(Direction.OUTBOUND, RuleFixtures.INSURER,
                RuleFixtures.PRODUCT, CommTypeCode.FY_COMM, null, Rate.of("6.5"),
                EffectivePeriod.from(LocalDate.of(2026, 8, 1))).rateId());
    }

    private Money netAmount(RecipientType type, String recipientId, CommTypeCode commType) {
        return harness.calcStore.all().stream()
                .filter(r -> r.recipientType() == type && r.recipientId().equals(recipientId)
                        && r.commType().equals(commType))
                .map(CommCalcRecord::calcAmount)
                .reduce(Money.ZERO, Money::plus);
    }

    @Test
    @DisplayName("골든: 소급 요율 변경 — reversal + rebook 후 순액과 원장이 새 요율 기준으로 수렴한다")
    void 소급_요율_변경_전체_시나리오() {
        CommissionCalculator calculator = harness.calculator();
        PolicyEvent stored = processNewContract(calculator);
        approveRetroactiveRate();

        RevisionService.RebookResult result =
                revision.rebookEvent(stored, calculator, "소급 요율 변경 7.0→6.5");

        // 원본 4건(설계사 1 + 오버라이드 3) 전부 reversal
        assertThat(result.reversals()).hasSize(4);
        assertThat(result.rebooked()).hasSize(4);

        // 설계사 순액 = 1,890,000 − 1,890,000 + 1,755,000
        assertThat(netAmount(RecipientType.AGENT, EventFixtures.AGENT_A.value(), CommTypeCode.FY_COMM))
                .isEqualTo(Money.won(1_755_000));
        // 팀 오버라이드 순액 = 97,500
        assertThat(netAmount(RecipientType.ORG, "T1", CommTypeCode.OVERRIDE))
                .isEqualTo(Money.won(97_500));

        // 원본은 REVERSED 마킹, reversal은 원본 참조
        List<CommCalcRecord> all = harness.calcStore.all();
        assertThat(all.stream().filter(r -> r.status() == CalcStatus.REVERSED)).hasSize(4);
        assertThat(result.reversals()).allSatisfy(r -> {
            assertThat(r.reversalOf()).isNotNull();
            assertThat(r.calcAmount().isNegative()).isTrue();
        });

        // 한도 원장도 새 요율 기준으로 수렴 + 불변식 유지
        LimitLedger ledger = ledgers.find(EventFixtures.POLICY_1, EventFixtures.AGENT_A).orElseThrow();
        assertThat(ledger.accumPaid()).isEqualTo(Money.won(1_755_000));
        assertThat(ledger.invariantHolds()).isTrue();
    }

    @Test
    @DisplayName("값 멱등: 같은 재계산을 두 번 돌려도 순액·원장은 동일하다")
    void 재계산_값_멱등성() {
        CommissionCalculator calculator = harness.calculator();
        PolicyEvent stored = processNewContract(calculator);
        approveRetroactiveRate();

        revision.rebookEvent(stored, calculator, "1차");
        Money netAfterFirst = netAmount(RecipientType.AGENT, EventFixtures.AGENT_A.value(),
                CommTypeCode.FY_COMM);
        Money ledgerAfterFirst = ledgers.find(EventFixtures.POLICY_1, EventFixtures.AGENT_A)
                .orElseThrow().accumPaid();

        revision.rebookEvent(stored, calculator, "2차(재시도)");

        assertThat(netAmount(RecipientType.AGENT, EventFixtures.AGENT_A.value(), CommTypeCode.FY_COMM))
                .isEqualTo(netAfterFirst);
        LimitLedger ledger = ledgers.find(EventFixtures.POLICY_1, EventFixtures.AGENT_A).orElseThrow();
        assertThat(ledger.accumPaid()).isEqualTo(ledgerAfterFirst);
        assertThat(ledger.invariantHolds()).isTrue();
    }

    @Test
    @DisplayName("reversal 멱등: 이미 REVERSED인 원본은 다시 취소되지 않는다")
    void reversal_멱등성() {
        CommissionCalculator calculator = harness.calculator();
        PolicyEvent stored = processNewContract(calculator);
        long calcId = harness.calcStore.findByEventId(stored.eventId()).get(0).calcId();

        assertThat(revision.reverse(calcId, "1차")).isPresent();
        assertThat(revision.reverse(calcId, "2차")).isEmpty();

        // reversal 레코드 자체는 다시 취소 불가
        long reversalId = harness.calcStore.all().stream()
                .filter(r -> r.reversalOf() != null).findFirst().orElseThrow().calcId();
        assertThatThrownBy(() -> revision.reverse(reversalId, "불가"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("마감월 정정: reversal은 이후 첫 OPEN 월로 귀속되고 원본 월 숫자는 불변이다")
    void 마감월_정정은_익월_귀속() {
        // 202608 마감 상태
        CloseStatusProvider closed202608 = ym -> ym.equals(CloseYm.of("202608"));
        harness.closeYmResolver = event -> closed202608.attributionFor(CloseYm.from(event.eventDate()));
        RevisionService closedRevision = new RevisionService(harness.calcStore, closed202608,
                List.of(new LimitReversalHook(ledgers)));

        CommissionCalculator calculator = harness.calculator();
        // 이벤트는 8월 발생이지만 8월이 마감이므로 202609로 귀속
        List<CommCalcRecord> records = calculator.process(EventFixtures.newContract());
        assertThat(records.get(0).closeYm().value()).isEqualTo("202609");

        PolicyEvent stored = EventFixtures.newContract().withEventId(records.get(0).eventId());
        approveRetroactiveRate();
        RevisionService.RebookResult result = closedRevision.rebookEvent(stored, calculator, "정정");

        // reversal·rebook 모두 OPEN 월(202609)로 귀속
        assertThat(result.reversals()).allSatisfy(r ->
                assertThat(r.closeYm().value()).isEqualTo("202609"));
        assertThat(result.rebooked()).allSatisfy(r ->
                assertThat(r.closeYm().value()).isEqualTo("202609"));
    }

    @Test
    @DisplayName("replay: 계산 시점 원장 상태로 동일 금액이 재산출되고, 소급 변경은 불일치로 검출된다")
    void replay_재현성_검증() {
        CommissionCalculator calculator = harness.calculator();
        List<CommCalcRecord> records = calculator.process(EventFixtures.newContract());
        CommCalcRecord agentRecord = records.stream()
                .filter(r -> r.recipientType() == RecipientType.AGENT).findFirst().orElseThrow();
        PolicyEvent stored = EventFixtures.newContract().withEventId(agentRecord.eventId());

        // 계산 시점 원장 재구성 = 해당 calc 이전 전기 없음 → 빈 원장 스토어
        CalcTestHarness replayHarness = new CalcTestHarness(harness.rules);
        replayHarness.addStep(new StepConfig(new LimitGateStep(new InMemoryLimitLedgerStore()),
                EffectivePeriod.from(LocalDate.of(2026, 7, 1)), 50));
        ReplayService replayService = new ReplayService(replayHarness.calculator());

        ReplayService.ReplayResult ok = replayService.replay(stored, agentRecord);
        assertThat(ok.matches()).as(ok.detail()).isTrue();

        // 소급 요율 변경 후에는 불일치가 검출된다 (감사: 룰이 바뀌었음을 드러냄)
        approveRetroactiveRate();
        ReplayService.ReplayResult changed = replayService.replay(stored, agentRecord);
        assertThat(changed.matches()).isFalse();
        assertThat(changed.replayedAmount()).isEqualTo(Money.won(1_755_000));
    }

    @Test
    @DisplayName("정합성: reversal 합계 = 원본 합계 × −1 (불변식 §8.2)")
    void reversal_합계_불변식() {
        CommissionCalculator calculator = harness.calculator();
        PolicyEvent stored = processNewContract(calculator);
        approveRetroactiveRate();
        RevisionService.RebookResult result = revision.rebookEvent(stored, calculator, "정정");

        Money originalSum = harness.calcStore.all().stream()
                .filter(r -> r.status() == CalcStatus.REVERSED)
                .map(CommCalcRecord::calcAmount)
                .reduce(Money.ZERO, Money::plus);
        Money reversalSum = result.reversals().stream()
                .map(CommCalcRecord::calcAmount)
                .reduce(Money.ZERO, Money::plus);

        assertThat(reversalSum).isEqualTo(originalSum.negate());
    }
}
