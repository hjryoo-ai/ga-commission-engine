package ga.comm.app.config;

import ga.comm.calc.recipient.RecipientResolver;
import ga.comm.calc.store.CloseYmResolver;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.calc.store.PolicyEventStore;
import ga.comm.deferral.DeferralScheduleStore;
import ga.comm.deferral.PolicyStatusProvider;
import ga.comm.infra.OraclePersistence;
import ga.comm.limit.LimitLedgerStore;
import ga.comm.rule.IncentiveRepository;
import ga.comm.rule.RuleRepository;
import ga.comm.settlement.WithholdingTaxPolicy;
import ga.comm.settlement.estimate.EstimateReads;
import ga.comm.settlement.simulation.DryRunSimulator;
import ga.comm.settlement.simulation.SimulationReads;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.function.Supplier;

/**
 * 시뮬레이션 배선(E2 ②). 시뮬레이터는 운영 저장소 빈의 <b>읽기 메서드 참조</b>만 받는다({@link SimulationReads} — 원장은 잠그지 않는
 * {@code peek}). 저장소 객체 자체를 넘기지 않으므로 발행 데코레이터의 쓰기 경로가 시뮬레이터 손에 없다. 호출마다 읽기 전용 트랜잭션 하나.
 */
@Configuration(proxyBeanMethods = false)
public class SimulationConfig {

    @Bean
    public SimulationReads simulationReads(CommCalcStore calcStore, LimitLedgerStore ledgerStore, DeferralScheduleStore scheduleStore,
                                           PolicyEventStore eventStore, OraclePersistence p) {
        var agents = p.agentMasterReader();
        return new SimulationReads(calcStore::findById, calcStore::findByEventId, calcStore::findByPolicyAndRecipient,
                calcStore::findByCloseYm, ledgerStore::peek, scheduleStore::findDue, scheduleStore::findByPolicyAndAgent,
                scheduleStore::findBySourceCalcId, eventStore::findById, id -> agents.find(id).isPresent());
    }

    @Bean
    public DryRunSimulator dryRunSimulator(RuleRepository ruleRepository, IncentiveRepository incentiveRepository,
                                           RecipientResolver recipientResolver, CloseYmResolver closeYmResolver,
                                           PolicyStatusProvider policyStatus, SimulationReads simulationReads, EstimateReads estimateReads,
                                           WithholdingTaxPolicy withholdingTaxPolicy, Clock clock,
                                           PlatformTransactionManager transactionManager) {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        return new DryRunSimulator(ruleRepository, incentiveRepository, recipientResolver, closeYmResolver, policyStatus,
                simulationReads, estimateReads, withholdingTaxPolicy, clock, new DryRunSimulator.ReadTx() {
                    @Override
                    public <T> T read(Supplier<T> work) {
                        return readOnly.execute(status -> work.get());
                    }
                });
    }
}
