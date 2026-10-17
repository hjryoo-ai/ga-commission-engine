package ga.comm.settlement.estimate;

import ga.comm.calc.store.CommCalcRecord;
import ga.comm.clawback.ClawbackReceivable;
import ga.comm.clawback.ReceivableStatus;
import ga.comm.deferral.ScheduleEntry;
import ga.comm.deferral.ScheduleStatus;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;
import ga.comm.domain.type.RecipientType;
import ga.comm.settlement.AdjustmentService;
import ga.comm.settlement.SettleCloseStore;
import ga.comm.settlement.WithholdingTaxPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** E2 ① — 산출기의 규칙(대상 설계사·마감월 거부·도래 미리보기·상계 미리보기). 금액 대조는 {@code EstimateEqualsActualGoldenTest}(C2). */
class StatementEstimatorTest {

    private static final CloseYm SEP = CloseYm.of("202609");
    private static final PolicyNo P1 = new PolicyNo("POL-1");

    private final List<CommCalcRecord> records = new ArrayList<>();
    private final List<ScheduleEntry> due = new ArrayList<>();
    private final List<AdjustmentService.Adjustment> adjustments = new ArrayList<>();
    private final Map<String, List<ClawbackReceivable>> receivables = new java.util.HashMap<>();
    private SettleCloseStore.CloseState state = SettleCloseStore.CloseState.OPEN;
    private boolean inforce = true;

    private StatementEstimator estimator() {
        return new StatementEstimator(new EstimateReads(ym -> records,
                id -> records.stream().filter(r -> r.calcId() != null && r.calcId() == id).findFirst(),
                ym -> due, (policyNo, ym) -> inforce, agent -> receivables.getOrDefault(agent.value(), List.of()),
                ym -> adjustments, ym -> state), WithholdingTaxPolicy.STANDARD_3_3);
    }

    private static CommCalcRecord record(long id, RecipientType type, String recipient, CommTypeCode commType, long amount) {
        return new CommCalcRecord(id, 1L, P1, type, recipient, commType, Money.won(amount), null, Money.won(amount), Money.ZERO, SEP,
                CalcStatus.CALCULATED, null, "[]", "[]");
    }

    @Test
    void 마감월은_내지_않는다() {
        records.add(record(1, RecipientType.AGENT, "A1", CommTypeCode.FY_COMM, 100_000));
        state = SettleCloseStore.CloseState.CLOSED;
        assertThatThrownBy(() -> estimator().estimate(SEP)).isInstanceOf(MonthClosedException.class);
        state = SettleCloseStore.CloseState.CLOSING;
        assertThatThrownBy(() -> estimator().estimate(SEP)).isInstanceOf(MonthClosedException.class);
        state = SettleCloseStore.CloseState.OPEN;
        assertThat(estimator().estimate(SEP)).hasSize(1);
    }

    @Test
    void 대상은_AGENT_레코드_도래분_정정이_있는_설계사이고_ORG는_제외된다() {
        records.add(record(1, RecipientType.AGENT, "A2", CommTypeCode.FY_COMM, 100_000));
        records.add(record(2, RecipientType.ORG, "T1", CommTypeCode.OVERRIDE, 10_000));
        due.add(new ScheduleEntry(9L, 1L, P1, new AgentId("A3"), SEP, Money.won(50_000), ScheduleEntry.CONDITION_POLICY_INFORCE,
                ScheduleStatus.SCHEDULED, 1L, null));
        records.add(record(3, RecipientType.AGENT, "A4", CommTypeCode.FY_COMM, 0));
        adjustments.add(new AdjustmentService.Adjustment(1L, 3L, "정정", Money.won(-5_000), SEP, "팀장"));
        assertThat(estimator().estimate(SEP)).extracting(StatementEstimator.Estimate::agentId).containsExactly("A2", "A3", "A4");
        StatementEstimator.Estimate a3 = estimator().estimate(SEP, "A3").orElseThrow();
        assertThat(a3.records()).singleElement().satisfies(r -> {
            assertThat(r.commType()).isEqualTo(CommTypeCode.DEFERRED);
            assertThat(r.calcId()).as("저장하지 않은 미리보기").isNull();
        });
        assertThat(a3.math().grossNet()).isEqualTo(Money.won(50_000));
        assertThat(estimator().estimate(SEP, "A4").orElseThrow().adjustment()).isEqualTo(Money.won(-5_000));
        assertThat(estimator().estimate(SEP, "T1")).isEmpty();
    }

    @Test
    void 유지_조건_미충족_도래분은_미리보기에_없다() {
        due.add(new ScheduleEntry(9L, 1L, P1, new AgentId("A3"), SEP, Money.won(50_000), ScheduleEntry.CONDITION_POLICY_INFORCE,
                ScheduleStatus.SCHEDULED, 1L, null));
        records.add(record(1, RecipientType.AGENT, "A3", CommTypeCode.FY_COMM, 100_000));
        inforce = false;
        assertThat(estimator().estimate(SEP, "A3").orElseThrow().math().grossNet()).isEqualTo(Money.won(100_000));
    }

    @Test
    void 상계_미리보기는_채권을_바꾸지_않는다() {
        records.add(record(1, RecipientType.AGENT, "A1", CommTypeCode.FY_COMM, 1_000_000));
        ClawbackReceivable r = ClawbackReceivable.rehydrate(1L, new AgentId("A1"), 5L, Money.won(300_000), Money.won(300_000),
                ReceivableStatus.OPEN);
        receivables.put("A1", List.of(r));
        StatementEstimator.Estimate e = estimator().estimate(SEP, "A1").orElseThrow();
        assertThat(e.math().receivableOffset()).isEqualTo(Money.won(300_000));
        assertThat(e.math().payable()).isEqualTo(Money.won(700_000));
        assertThat(r.remaining()).isEqualTo(Money.won(300_000));
        assertThat(r.status()).isEqualTo(ReceivableStatus.OPEN);
        assertThat(Optional.of(estimator().estimate(SEP, "A1").orElseThrow().math().receivableOffset())).contains(Money.won(300_000));
    }
}
