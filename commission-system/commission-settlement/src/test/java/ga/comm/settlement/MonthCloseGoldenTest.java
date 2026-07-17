package ga.comm.settlement;

import ga.comm.calc.StepConfig;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.calc.revision.RevisionService;
import ga.comm.calc.store.CloseStatusProvider;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.clawback.ClawbackOffsetService;
import ga.comm.clawback.ClawbackPostProcessor;
import ga.comm.clawback.ClawbackStep;
import ga.comm.clawback.fixture.InMemoryClawbackReceivableStore;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;
import ga.comm.domain.type.Direction;
import ga.comm.domain.type.EventType;
import ga.comm.domain.type.RecipientType;
import ga.comm.limit.LimitGateStep;
import ga.comm.limit.LimitLedgerPoster;
import ga.comm.limit.LimitLedgerService;
import ga.comm.limit.LimitReversalHook;
import ga.comm.limit.fixture.InMemoryLimitLedgerStore;
import ga.comm.rule.admin.RateApprovalService;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.settlement.fixture.InMemoryAdjustmentStore;
import ga.comm.settlement.fixture.InMemoryAgentSettlementStore;
import ga.comm.settlement.fixture.InMemorySettleCloseStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 6 골든 케이스 (설계서 §7, §10): 월마감 체크리스트, 지급 런(상계·채권화·원천세),
 * 마감월 불변성. v1.1 §7: 상계·원천세·정산행은 마감이 아니라 지급 런의 일이다.
 */
class MonthCloseGoldenTest {

    private CalcTestHarness harness;
    private InMemoryLimitLedgerStore ledgers;
    private InMemoryClawbackReceivableStore receivables;
    private InMemorySettleCloseStore closeStore;
    private InMemoryAgentSettlementStore settlementStore;
    private MonthCloseService closeService;
    private PayoutService payoutService;

    @BeforeEach
    void setUp() {
        harness = new CalcTestHarness();
        ledgers = new InMemoryLimitLedgerStore();
        receivables = new InMemoryClawbackReceivableStore();
        closeStore = new InMemorySettleCloseStore();
        settlementStore = new InMemoryAgentSettlementStore();

        harness.addStep(new StepConfig(new ClawbackStep(harness.calcStore),
                EffectivePeriod.from(LocalDate.of(2026, 1, 1)), 45));
        harness.addStep(new StepConfig(new LimitGateStep(ledgers),
                EffectivePeriod.from(LocalDate.of(2026, 7, 1)), 50));
        harness.addListener(new LimitLedgerPoster(ledgers));
        harness.addListener(new ClawbackPostProcessor(new LimitLedgerService(ledgers)));

        // 귀속월은 마감 상태를 따른다 (CLOSED면 다음 OPEN 월)
        CloseStatusProvider provider = SettleCloseStore.asProvider(closeStore);
        harness.closeYmResolver = event -> provider.attributionFor(CloseYm.from(event.eventDate()));

        ClawbackOffsetService offsetService = new ClawbackOffsetService(receivables);
        closeService = new MonthCloseService(harness.calcStore, harness.eventStore, ledgers,
                closeStore, List.of());
        payoutService = new PayoutService(harness.calcStore, settlementStore, closeStore,
                offsetService, WithholdingTaxPolicy.STANDARD_3_3);
    }

    private SettlementRow agentRow(PayoutService.RunResult run) {
        return run.settlements().stream()
                .filter(r -> r.recipientType() == RecipientType.AGENT).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("골든: 마감→확정→지급 런 사이클 — 원천세 3.3% 절사, PAID 전이")
    void 정상_마감_지급_사이클() {
        harness.calculator().process(EventFixtures.newContract());

        MonthCloseService.CloseResult result = closeService.close(CloseYm.of("202608"), "정산담당");

        assertThat(result.closed()).isTrue();
        assertThat(result.checklist()).allSatisfy(c -> assertThat(c.passed()).isTrue());
        assertThat(harness.calcStore.findByCloseYm(CloseYm.of("202608")))
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(CalcStatus.CONFIRMED));

        PayoutService.RunResult run = payoutService.run(CloseYm.of("202608"), 1);
        assertThat(agentRow(run).payable()).isEqualTo(Money.won(1_890_000));

        PayoutService.PayoutStatement agentStatement = run.statements().stream()
                .filter(s -> s.recipientType() == RecipientType.AGENT).findFirst().orElseThrow();
        // 소득세 1,890,000×3% = 56,700 / 지방소득세 ×0.3% = 5,670 → 실지급 1,827,630
        assertThat(agentStatement.incomeTax()).isEqualTo(Money.won(56_700));
        assertThat(agentStatement.localTax()).isEqualTo(Money.won(5_670));
        assertThat(agentStatement.netPay()).isEqualTo(Money.won(1_827_630));

        assertThat(harness.calcStore.findByCloseYm(CloseYm.of("202608")))
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(CalcStatus.PAID));
    }

    @Test
    @DisplayName("지급 런 반복: 2회차 런은 새 대상이 없으면 no-op — 이중 지급이 없다")
    void 지급_런_반복은_이중_지급이_없다() {
        harness.calculator().process(EventFixtures.newContract());
        closeService.close(CloseYm.of("202608"), "정산담당");

        PayoutService.RunResult first = payoutService.run(CloseYm.of("202608"), 1);
        assertThat(first.statements()).isNotEmpty();

        PayoutService.RunResult second = payoutService.run(CloseYm.of("202608"), 2);
        assertThat(second.statements()).isEmpty();
        assertThat(second.settlements()).isEmpty();
        assertThat(settlementStore.findByCloseYm(CloseYm.of("202608")))
                .hasSameSizeAs(first.settlements());
    }

    @Test
    @DisplayName("체크리스트: 미처리 이벤트가 있으면 마감되지 않고 OPEN으로 되돌아간다")
    void 미처리_이벤트_마감_차단() {
        harness.calculator().process(EventFixtures.newContract());
        // 수신만 되고 처리되지 않은 이벤트
        harness.eventStore.upsertByKey(EventFixtures.payment(2, LocalDate.of(2026, 8, 20)));

        MonthCloseService.CloseResult result = closeService.close(CloseYm.of("202608"), "정산담당");

        assertThat(result.closed()).isFalse();
        assertThat(closeStore.stateOf(CloseYm.of("202608")))
                .isEqualTo(SettleCloseStore.CloseState.OPEN);
        assertThat(harness.calcStore.findByCloseYm(CloseYm.of("202608")))
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(CalcStatus.CALCULATED));
    }

    @Test
    @DisplayName("강제 마감은 사유가 기록되고, 사유 없이는 거부된다")
    void 강제_마감() {
        harness.calculator().process(EventFixtures.newContract());
        harness.eventStore.upsertByKey(EventFixtures.payment(2, LocalDate.of(2026, 8, 20)));

        assertThatThrownBy(() -> closeService.close(CloseYm.of("202608"), "팀장", true, " "))
                .isInstanceOf(IllegalArgumentException.class);

        MonthCloseService.CloseResult result =
                closeService.close(CloseYm.of("202608"), "팀장", true, "보험사 지연분 익월 반영 승인");
        assertThat(result.closed()).isTrue();
        assertThat(result.checklist()).anySatisfy(c -> {
            assertThat(c.name()).isEqualTo("강제 마감");
            assertThat(c.detail()).contains("팀장");
        });
    }

    @Test
    @DisplayName("골든: 환수>지급 월은 지급 런에서 채권화되고, 익월 지급 런에서 자동 상계된다")
    void 환수_채권화_익월_상계() {
        // 8월: 신계약 지급 → 마감 → 지급 런
        harness.calculator().process(EventFixtures.newContract());
        closeService.close(CloseYm.of("202608"), "정산담당");
        payoutService.run(CloseYm.of("202608"), 1);

        // 9월: 2회차 경과 해약 → 환수 70% = 1,323,000 → 월 순액 음수
        harness.calculator().process(EventFixtures.terminal(EventType.CANCEL,
                EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE, LocalDate.of(2026, 9, 15), 2));
        closeService.close(CloseYm.of("202609"), "정산담당");
        PayoutService.RunResult sept = payoutService.run(CloseYm.of("202609"), 1);

        SettlementRow septRow = agentRow(sept);
        assertThat(septRow.grossNet()).isEqualTo(Money.won(-1_323_000));
        assertThat(septRow.carriedReceivable()).isEqualTo(Money.won(1_323_000));
        assertThat(septRow.payable()).isEqualTo(Money.ZERO);
        assertThat(receivables.all()).hasSize(1);

        // 10월: 다른 계약 신계약 1,890,000 발생 → 지급 런에서 채권 자동 상계 후 잔액 지급
        harness.calculator().process(EventFixtures.newContract(
                new ga.comm.domain.id.PolicyNo("POL-2026-0002"), LocalDate.of(2026, 10, 1),
                Money.won(300_000), Map.of()));
        closeService.close(CloseYm.of("202610"), "정산담당");
        PayoutService.RunResult oct = payoutService.run(CloseYm.of("202610"), 1);

        SettlementRow octRow = agentRow(oct);
        assertThat(octRow.grossNet()).isEqualTo(Money.won(1_890_000));
        assertThat(octRow.receivableOffset()).isEqualTo(Money.won(1_323_000));
        assertThat(octRow.payable()).isEqualTo(Money.won(567_000));
        assertThat(receivables.all().get(0).remaining()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("합산 규약(§3.0): 마감 전 정정이 있어도 지급 런 순액은 이중 차감 없이 rebook 금액이다")
    void 마감_전_정정은_이중_차감_없이_정산된다() {
        var calculator = harness.calculator();
        List<CommCalcRecord> records = calculator.process(EventFixtures.newContract());
        var stored = EventFixtures.newContract().withEventId(records.get(0).eventId());

        // 소급 요율 7.0 → 6.5 승인 후 마감 전 재계산 — 원본(REVERSED)·reversal·rebook이 같은 월에 공존
        RateApprovalService approval = new RateApprovalService(harness.rules);
        approval.approve(approval.registerDraft(Direction.OUTBOUND, RuleFixtures.INSURER,
                RuleFixtures.PRODUCT, CommTypeCode.FY_COMM, null, Rate.of("6.5"),
                EffectivePeriod.from(LocalDate.of(2026, 8, 1))).rateId());
        RevisionService revision = new RevisionService(harness.calcStore,
                SettleCloseStore.asProvider(closeStore), List.of(new LimitReversalHook(ledgers)));
        revision.rebookEvent(stored, calculator, "마감 전 소급 정정");

        closeService.close(CloseYm.of("202608"), "정산담당");
        PayoutService.RunResult run = payoutService.run(CloseYm.of("202608"), 1);

        // 전 상태 합산: +1,890,000(REVERSED 원본) −1,890,000(reversal) +1,755,000(rebook)
        // 상태 필터 합산이었다면 −135,000으로 채권화되는 잘못된 결과가 나온다
        SettlementRow row = agentRow(run);
        assertThat(row.grossNet()).isEqualTo(Money.won(1_755_000));
        assertThat(row.payable()).isEqualTo(Money.won(1_755_000));
        assertThat(receivables.all()).isEmpty();
    }

    @Test
    @DisplayName("불변성: 마감월로의 신규 귀속은 차단된다 — 새 계산은 다음 OPEN 월로 귀속")
    void 마감월_불변성() {
        List<CommCalcRecord> augustRecords = harness.calculator().process(EventFixtures.newContract());
        closeService.close(CloseYm.of("202608"), "정산담당");

        // ① 가드: 마감월 직접 insert 차단
        ClosedMonthGuardedCalcStore guarded =
                new ClosedMonthGuardedCalcStore(harness.calcStore, closeStore);
        CommCalcRecord intoClosed = new CommCalcRecord(null, augustRecords.get(0).eventId(),
                EventFixtures.POLICY_1, RecipientType.AGENT, EventFixtures.AGENT_A.value(),
                ga.comm.domain.id.CommTypeCode.INCENTIVE, Money.won(100), Rate.of("1"),
                Money.won(100), Money.ZERO, CloseYm.of("202608"), CalcStatus.CALCULATED,
                null, "[]", "[]");
        assertThatThrownBy(() -> guarded.insert(intoClosed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("마감월");

        // ② 8월 발생 이벤트가 늦게 도착해도 귀속은 202609
        List<CommCalcRecord> late = harness.calculator().process(
                EventFixtures.payment(2, LocalDate.of(2026, 8, 25)));
        assertThat(late).allSatisfy(r -> assertThat(r.closeYm().value()).isEqualTo("202609"));
    }

    @Test
    @DisplayName("ADJUSTMENT: 마감월 건 정정은 익월 귀속, 미마감월 건은 거부")
    void 조정은_익월_귀속() {
        List<CommCalcRecord> records = harness.calculator().process(EventFixtures.newContract());
        long calcId = records.get(0).calcId();

        InMemoryAdjustmentStore adjustmentStore = new InMemoryAdjustmentStore();
        AdjustmentService adjustmentService = new AdjustmentService(harness.calcStore,
                SettleCloseStore.asProvider(closeStore), adjustmentStore);

        // 미마감월 → 거부 (Reversal&Rebook 대상)
        assertThatThrownBy(() -> adjustmentService.adjust(calcId, "입력 오류", Money.won(-10_000), "관리자"))
                .isInstanceOf(IllegalStateException.class);

        closeService.close(CloseYm.of("202608"), "정산담당");

        AdjustmentService.Adjustment adjustment =
                adjustmentService.adjust(calcId, "입력 오류", Money.won(-10_000), "관리자");
        assertThat(adjustment.closeYm().value()).isEqualTo("202609");
    }

    @Test
    @DisplayName("이중 마감·마감 전 지급 런은 거부된다")
    void 이중_마감과_조기_지급_거부() {
        harness.calculator().process(EventFixtures.newContract());

        assertThatThrownBy(() -> payoutService.run(CloseYm.of("202608"), 1))
                .isInstanceOf(IllegalStateException.class);

        closeService.close(CloseYm.of("202608"), "정산담당");
        assertThatThrownBy(() -> closeService.close(CloseYm.of("202608"), "정산담당"))
                .isInstanceOf(IllegalStateException.class);
    }
}
