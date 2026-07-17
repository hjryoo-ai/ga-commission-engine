package ga.comm.infra.it;

import ga.comm.batch.BatchRequestRunner;
import ga.comm.batch.job.MonthCloseJobFactory;
import ga.comm.batch.job.PayoutRunJobFactory;
import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.clawback.ClawbackOffsetService;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;
import ga.comm.domain.type.RecipientType;
import ga.comm.infra.OraclePersistence;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.settlement.AgentSettlementStore;
import ga.comm.settlement.MonthCloseService;
import ga.comm.settlement.PayoutService;
import ga.comm.settlement.SettlementRow;
import ga.comm.settlement.WithholdingTaxPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.StepExecution;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지급 런 잡 (실 Oracle, Phase 11 §7 D+1~, §11.12): 런 반복(월 N회)·항목 격리·
 * 격리 항목 트랜잭션 롤백(상계 원복)·상계와 원천세의 런 단위 정확성.
 */
class OraclePayoutRunJobIT {

    /** 특정 수급자의 정산행 저장만 실패시키는 장애 주입 — 상계 이후 지점이라 롤백이 증명된다. */
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

    private final OraclePersistence persistence = OracleTestSupport.persistence();
    private final FaultInjectingSettlementStore settlementStore =
            new FaultInjectingSettlementStore(persistence.agentSettlementStore());

    @BeforeEach
    void clean() {
        OracleTestSupport.cleanAll();
    }

    private Job closeJob() {
        return MonthCloseJobFactory.job(OracleBatchSupport.runtime().jobRepository(),
                persistence.txManager(),
                new MonthCloseService(persistence.commCalcStore(), persistence.policyEventStore(),
                        persistence.limitLedgerStore(), persistence.settleCloseStore(), List.of()),
                List.of());
    }

    private Job payoutJob() {
        PayoutService payoutService = new PayoutService(persistence.commCalcStore(),
                settlementStore, persistence.settleCloseStore(),
                new ClawbackOffsetService(persistence.clawbackReceivableStore()),
                WithholdingTaxPolicy.STANDARD_3_3);
        return PayoutRunJobFactory.job(OracleBatchSupport.runtime().jobRepository(),
                persistence.txTemplate(), payoutService);
    }

    private SettlementRow rowOf(String closeYm, String recipientId) {
        return persistence.inTx(() -> settlementStore.findByCloseYm(CloseYm.of(closeYm))).stream()
                .filter(r -> r.recipientId().equals(recipientId)).findFirst().orElseThrow();
    }

    private static StepExecution soleStep(BatchRequestRunner.Outcome outcome) {
        return outcome.execution().getStepExecutions().iterator().next();
    }

    @Test
    void 지급_런_반복_항목_격리_롤백_상계_원천세() {
        CommissionCalculator calculator =
                OracleBatchSupport.calculator(persistence, RuleFixtures.standardRules());
        persistence.inTx(() -> calculator.process(EventFixtures.newContract()));

        // 설계사 A에 기존 환수 채권 2,000,000 — 두 달에 걸친 런 단위 상계 반복을 본다
        persistence.inTx(() -> persistence.clawbackReceivableStore()
                .create(EventFixtures.AGENT_A, null, Money.won(2_000_000)));

        assertThat(OracleBatchSupport.runner().submit(closeJob(),
                OracleBatchSupport.closeParams("202608", "정산담당", false, null)).disposition())
                .isEqualTo(BatchRequestRunner.Disposition.COMPLETED);

        Job payout = payoutJob();

        // 런 1: 설계사 A의 정산행 저장 실패 주입 — 상계(consume) 이후 지점에서 터진다
        settlementStore.failFor = EventFixtures.AGENT_A.value();
        BatchRequestRunner.Outcome run1 = OracleBatchSupport.runner().submit(payout,
                OracleBatchSupport.payoutParams("202608", 1));
        assertThat(run1.disposition()).isEqualTo(BatchRequestRunner.Disposition.COMPLETED);
        assertThat(soleStep(run1).getExitStatus().getExitCode())
                .isEqualTo(PayoutRunJobFactory.EXIT_WITH_SKIPS);
        assertThat(run1.execution().getExecutionContext()
                .getString(PayoutRunJobFactory.CTX_ISOLATED)).contains("AGENT:A-1001");

        // 조직 3곳만 정산됐고, A의 트랜잭션은 통째로 롤백됐다 —
        // consume()이 줄였던 채권 잔액이 실 Oracle 롤백으로 원복되어 있어야 한다 (§6.1.6 원자성)
        assertThat(persistence.inTx(() -> settlementStore.findByCloseYm(CloseYm.of("202608"))))
                .hasSize(3)
                .noneMatch(r -> r.recipientId().equals(EventFixtures.AGENT_A.value()));
        assertThat(persistence.inTx(() -> persistence.clawbackReceivableStore()
                .findOffsettable(EventFixtures.AGENT_A)).get(0).remaining())
                .isEqualTo(Money.won(2_000_000));
        assertThat(persistence.inTx(() -> persistence.commCalcStore()
                .findByPolicyAndRecipient(EventFixtures.POLICY_1, EventFixtures.AGENT_A.value())))
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(CalcStatus.CONFIRMED));

        // 런 2 (장애 해소): 격리됐던 A만 정산 — 순액 1,890,000 전액이 채권과 상계되어 지급 0
        settlementStore.failFor = null;
        BatchRequestRunner.Outcome run2 = OracleBatchSupport.runner().submit(payout,
                OracleBatchSupport.payoutParams("202608", 2));
        assertThat(run2.disposition()).isEqualTo(BatchRequestRunner.Disposition.COMPLETED);

        SettlementRow agentAug = rowOf("202608", EventFixtures.AGENT_A.value());
        assertThat(agentAug.runSeq()).isEqualTo(2);
        assertThat(agentAug.grossNet()).isEqualTo(Money.won(1_890_000));
        assertThat(agentAug.receivableOffset()).isEqualTo(Money.won(1_890_000));
        assertThat(agentAug.payable()).isEqualTo(Money.ZERO);
        assertThat(persistence.inTx(() -> settlementStore.findByCloseYm(CloseYm.of("202608"))))
                .hasSize(4);
        assertThat(persistence.inTx(() -> persistence.commCalcStore()
                .findByPolicyAndRecipient(EventFixtures.POLICY_1, EventFixtures.AGENT_A.value())))
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(CalcStatus.PAID));
        assertThat(soleStep(run2).getExecutionContext()
                .getLong(PayoutRunJobFactory.CTX_INCOME_TAX, -1)).isZero();

        // 같은 런 재제출은 no-op, 새 런(3회차)은 대상이 없어 무해
        assertThat(OracleBatchSupport.runner().submit(payout,
                OracleBatchSupport.payoutParams("202608", 2)).disposition())
                .isEqualTo(BatchRequestRunner.Disposition.DEDUP_NOOP);
        BatchRequestRunner.Outcome run3 = OracleBatchSupport.runner().submit(payout,
                OracleBatchSupport.payoutParams("202608", 3));
        assertThat(run3.disposition()).isEqualTo(BatchRequestRunner.Disposition.COMPLETED);
        assertThat(soleStep(run3).getExecutionContext()
                .getInt(PayoutRunJobFactory.CTX_SETTLED, 0)).isZero();
        assertThat(persistence.inTx(() -> settlementStore.findByCloseYm(CloseYm.of("202608"))))
                .hasSize(4);

        // 9월: 새 계약 → 마감 → 런 1 — 잔여 채권 110,000이 이번 런에서 마저 상계되고
        // 원천세는 상계 후 순지급액 기준으로 런 안에서 계산된다
        persistence.inTx(() -> calculator.process(EventFixtures.newContract(
                new PolicyNo("POL-2026-0002"), LocalDate.of(2026, 9, 1), Money.won(300_000), Map.of())));
        assertThat(OracleBatchSupport.runner().submit(closeJob(),
                OracleBatchSupport.closeParams("202609", "정산담당", false, null)).disposition())
                .isEqualTo(BatchRequestRunner.Disposition.COMPLETED);
        BatchRequestRunner.Outcome sept = OracleBatchSupport.runner().submit(payout,
                OracleBatchSupport.payoutParams("202609", 1));
        assertThat(sept.disposition()).isEqualTo(BatchRequestRunner.Disposition.COMPLETED);

        SettlementRow agentSept = rowOf("202609", EventFixtures.AGENT_A.value());
        assertThat(agentSept.receivableOffset()).isEqualTo(Money.won(110_000));
        assertThat(agentSept.payable()).isEqualTo(Money.won(1_780_000));

        // 런 원천세 합계: 설계사 1,780,000 + 조직 105,000/63,000/42,000
        //   소득세 3%: 53,400+3,150+1,890+1,260 = 59,700 / 지방소득세 0.3%: 5,970
        //   실지급 합계: 1,721,260+101,535+60,921+40,614 = 1,924,330
        StepExecution septStep = soleStep(sept);
        assertThat(septStep.getExecutionContext()
                .getLong(PayoutRunJobFactory.CTX_INCOME_TAX, -1)).isEqualTo(59_700L);
        assertThat(septStep.getExecutionContext()
                .getLong(PayoutRunJobFactory.CTX_LOCAL_TAX, -1)).isEqualTo(5_970L);
        assertThat(septStep.getExecutionContext()
                .getLong(PayoutRunJobFactory.CTX_NET_PAY, -1)).isEqualTo(1_924_330L);
        assertThat(persistence.inTx(() -> persistence.commCalcStore()
                .findByCloseYm(CloseYm.of("202609"))))
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(CalcStatus.PAID));
    }
}
