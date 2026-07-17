package ga.comm.app.config;

import ga.comm.calc.recipient.AgentDirectory;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.calc.store.PolicyEventStore;
import ga.comm.clawback.ClawbackReceivableStore;
import ga.comm.deferral.DeferralScheduleStore;
import ga.comm.inbound.InboundStatementStore;
import ga.comm.infra.OraclePersistence;
import ga.comm.infra.store.OracleIncentiveApprovalRunner;
import ga.comm.infra.store.OracleRateApprovalRunner;
import ga.comm.limit.LimitLedgerStore;
import ga.comm.rule.IncentiveRepository;
import ga.comm.rule.RuleRepository;
import ga.comm.rule.admin.CommRateAdminStore;
import ga.comm.rule.admin.IncentiveAdminStore;
import ga.comm.settlement.AdjustmentService;
import ga.comm.settlement.AgentSettlementStore;
import ga.comm.settlement.CloseReport;
import ga.comm.settlement.SettleCloseStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;

/**
 * 영속성 배선 (Phase 16) — 외부화된 {@link DataSource}(spring.datasource.*)로 {@link OraclePersistence}를
 * 조립하고, 그 팩토리들을 빈으로 노출한다. 트랜잭션 경계(§4.2 [3.5]~[5.5])는 여전히 {@code inTx}/
 * {@code TransactionTemplate}로 명시적으로 잡으며, {@code transactionManager} 빈을 두어 부트의 자동
 * 트랜잭션 매니저가 뒤로 물러나게 한다(업무 TM = 배치 메타 TM 동일 인스턴스, 락 회피는 step 레벨에서).
 */
@Configuration(proxyBeanMethods = false)
public class PersistenceConfig {

    @Bean
    public OraclePersistence oraclePersistence(DataSource dataSource) {
        return new OraclePersistence(dataSource);
    }

    @Bean
    public PlatformTransactionManager transactionManager(OraclePersistence p) {
        return p.txManager();
    }

    @Bean
    public TransactionTemplate transactionTemplate(OraclePersistence p) {
        return p.txTemplate();
    }

    @Bean
    public PolicyEventStore policyEventStore(OraclePersistence p) {
        return p.policyEventStore();
    }

    @Bean
    public CommCalcStore commCalcStore(OraclePersistence p) {
        return p.commCalcStore();
    }

    @Bean
    public LimitLedgerStore limitLedgerStore(OraclePersistence p) {
        return p.limitLedgerStore();
    }

    @Bean
    public DeferralScheduleStore deferralScheduleStore(OraclePersistence p) {
        return p.deferralScheduleStore();
    }

    @Bean
    public ClawbackReceivableStore clawbackReceivableStore(OraclePersistence p) {
        return p.clawbackReceivableStore();
    }

    @Bean
    public SettleCloseStore settleCloseStore(OraclePersistence p) {
        return p.settleCloseStore();
    }

    @Bean
    public AgentSettlementStore agentSettlementStore(OraclePersistence p) {
        return p.agentSettlementStore();
    }

    @Bean
    public AdjustmentService.AdjustmentStore adjustmentStore(OraclePersistence p) {
        return p.adjustmentStore();
    }

    @Bean
    public InboundStatementStore inboundStatementStore(OraclePersistence p) {
        return p.inboundStatementStore();
    }

    @Bean
    public RuleRepository ruleRepository(OraclePersistence p) {
        return p.ruleRepository();
    }

    @Bean
    public CommRateAdminStore commRateAdminStore(OraclePersistence p) {
        return p.commRateAdminStore();
    }

    @Bean
    public IncentiveAdminStore incentiveAdminStore(OraclePersistence p) {
        return p.incentiveAdminStore();
    }

    @Bean
    public IncentiveRepository incentiveRepository(OraclePersistence p) {
        return p.incentiveRepository();
    }

    @Bean
    public AgentDirectory agentDirectory(OraclePersistence p) {
        return p.agentDirectory();
    }

    @Bean
    public List<CloseReport> closeReports(OraclePersistence p) {
        return p.closeReports();
    }

    @Bean
    public OracleRateApprovalRunner rateApprovalRunner(OraclePersistence p) {
        return p.rateApprovalRunner();
    }

    @Bean
    public OracleIncentiveApprovalRunner incentiveApprovalRunner(OraclePersistence p) {
        return p.incentiveApprovalRunner();
    }
}
