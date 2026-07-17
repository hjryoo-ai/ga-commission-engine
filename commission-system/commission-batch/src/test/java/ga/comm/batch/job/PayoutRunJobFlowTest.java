package ga.comm.batch.job;

import ga.comm.batch.BatchRequestRunner;
import ga.comm.batch.BatchTestSupport;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.clawback.ClawbackOffsetService;
import ga.comm.clawback.fixture.InMemoryClawbackReceivableStore;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.RecipientType;
import ga.comm.limit.fixture.InMemoryLimitLedgerStore;
import ga.comm.settlement.AgentSettlementStore;
import ga.comm.settlement.MonthCloseService;
import ga.comm.settlement.PayoutService;
import ga.comm.settlement.SettlementRow;
import ga.comm.settlement.WithholdingTaxPolicy;
import ga.comm.settlement.fixture.InMemoryAgentSettlementStore;
import ga.comm.settlement.fixture.InMemorySettleCloseStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지급 런 잡 흐름 (설계서 §7 D+1~): 실패 수급자 항목 격리 → 다음 런에서 수습, 이중 지급 없음,
 * 상계는 런 단위로 호출된다.
 */
class PayoutRunJobFlowTest {

    /** 특정 수급자의 정산행 저장만 실패시키는 장애 주입 래퍼 — 첫 변이 지점이라 부분 작업이 없다. */
    static final class FaultInjectingSettlementStore implements AgentSettlementStore {
        private final AgentSettlementStore delegate;
        volatile String failFor;

        FaultInjectingSettlementStore(AgentSettlementStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void saveAll(List<SettlementRow> rows) {
            if (failFor != null && rows.stream().anyMatch(r -> r.recipientId().equals(failFor))) {
                throw new IllegalStateException("이체 계좌 오류(주입): " + failFor);
            }
            delegate.saveAll(rows);
        }

        @Override
        public List<SettlementRow> findByCloseYm(CloseYm closeYm) {
            return delegate.findByCloseYm(closeYm);
        }
    }

    private final BatchTestSupport.Env env = BatchTestSupport.env();
    private final CalcTestHarness harness = new CalcTestHarness();
    private final InMemorySettleCloseStore closeStore = new InMemorySettleCloseStore();
    private final InMemoryClawbackReceivableStore receivables = new InMemoryClawbackReceivableStore();
    private final FaultInjectingSettlementStore settlementStore =
            new FaultInjectingSettlementStore(new InMemoryAgentSettlementStore());

    @Test
    @DisplayName("실패 수급자는 격리되고, 다음 런이 수습한다 — 이중 지급 없음, 상계는 런 단위")
    void 항목_격리와_다음_런_수습() {
        harness.calculator().process(EventFixtures.newContract());
        new MonthCloseService(harness.calcStore, harness.eventStore,
                new InMemoryLimitLedgerStore(), closeStore, List.of())
                .close(CloseYm.of("202608"), "정산담당");

        // 설계사 A에 기존 채권 300,000 — 상계가 지급 런에서 일어남을 본다
        receivables.create(EventFixtures.AGENT_A, null, Money.won(300_000));

        PayoutService payoutService = new PayoutService(harness.calcStore, settlementStore,
                closeStore, new ClawbackOffsetService(receivables), WithholdingTaxPolicy.STANDARD_3_3);
        Job job = PayoutRunJobFactory.job(env.runtime().jobRepository(),
                new TransactionTemplate(new ResourcelessTransactionManager()), payoutService);

        // 런 1: 조직 T1의 정산행 저장 실패 주입 → T1만 격리, 나머지 3 수급자는 정산
        settlementStore.failFor = "T1";
        BatchRequestRunner.Outcome run1 = env.runner().submit(job,
                BatchTestSupport.payoutParams("202608", 1));
        assertThat(run1.disposition()).isEqualTo(BatchRequestRunner.Disposition.COMPLETED);
        assertThat(run1.execution().getExecutionContext()
                .getString(PayoutRunJobFactory.CTX_ISOLATED)).contains("ORG:T1");

        List<SettlementRow> afterRun1 = settlementStore.findByCloseYm(CloseYm.of("202608"));
        assertThat(afterRun1).hasSize(3);
        assertThat(afterRun1).noneMatch(r -> r.recipientId().equals("T1"));

        // 설계사 A: 순액 1,890,000에서 채권 300,000이 런 안에서 상계됐다
        SettlementRow agentRow = afterRun1.stream()
                .filter(r -> r.recipientType() == RecipientType.AGENT).findFirst().orElseThrow();
        assertThat(agentRow.receivableOffset()).isEqualTo(Money.won(300_000));
        assertThat(agentRow.payable()).isEqualTo(Money.won(1_590_000));
        assertThat(receivables.all().get(0).remaining()).isEqualTo(Money.ZERO);

        // 런 2 (장애 해소): 격리됐던 T1만 정산된다 — 기정산 수급자 재지급 없음
        settlementStore.failFor = null;
        BatchRequestRunner.Outcome run2 = env.runner().submit(job,
                BatchTestSupport.payoutParams("202608", 2));
        assertThat(run2.disposition()).isEqualTo(BatchRequestRunner.Disposition.COMPLETED);

        List<SettlementRow> afterRun2 = settlementStore.findByCloseYm(CloseYm.of("202608"));
        assertThat(afterRun2).hasSize(4);
        SettlementRow t1Row = afterRun2.stream()
                .filter(r -> r.recipientId().equals("T1")).findFirst().orElseThrow();
        assertThat(t1Row.runSeq()).isEqualTo(2);
        // 수급자별 정산행은 정확히 1건 — 이중 지급 없음
        assertThat(afterRun2.stream().map(SettlementRow::recipientId).distinct()).hasSize(4);

        // 같은 런 재제출은 dedup no-op
        assertThat(env.runner().submit(job, BatchTestSupport.payoutParams("202608", 2))
                .disposition()).isEqualTo(BatchRequestRunner.Disposition.DEDUP_NOOP);
        assertThat(settlementStore.findByCloseYm(CloseYm.of("202608"))).hasSize(4);
    }
}
