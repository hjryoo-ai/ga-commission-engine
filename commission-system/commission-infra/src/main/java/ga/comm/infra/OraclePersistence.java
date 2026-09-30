package ga.comm.infra;

import ga.comm.calc.store.CommCalcStore;
import ga.comm.calc.store.PolicyEventStore;
import ga.comm.clawback.ClawbackReceivableStore;
import ga.comm.deferral.DeferralScheduleStore;
import ga.comm.inbound.InboundStatementStore;
import ga.comm.calc.recipient.AgentDirectory;
import ga.comm.infra.mapper.AdjustmentMapper;
import ga.comm.infra.mapper.AgentDirectoryMapper;
import ga.comm.infra.mapper.AgentSettlementMapper;
import ga.comm.infra.mapper.CloseReportMapper;
import ga.comm.infra.mapper.CommRateAdminMapper;
import ga.comm.infra.mapper.RuleQueryMapper;
import ga.comm.infra.report.OracleCloseReports;
import ga.comm.infra.mapper.ClawbackReceivableMapper;
import ga.comm.infra.mapper.CommCalcMapper;
import ga.comm.infra.mapper.DeferralScheduleMapper;
import ga.comm.infra.mapper.InboundStatementMapper;
import ga.comm.infra.mapper.LimitLedgerMapper;
import ga.comm.infra.mapper.PolicyEventMapper;
import ga.comm.infra.mapper.SettleCloseMapper;
import ga.comm.infra.store.OracleAdjustmentStore;
import ga.comm.infra.store.OracleAgentDirectory;
import ga.comm.infra.store.OracleAgentSettlementStore;
import ga.comm.infra.store.OracleCommRateAdminStore;
import ga.comm.infra.store.OracleRateApprovalRunner;
import ga.comm.infra.store.OracleRuleRepository;
import ga.comm.infra.store.OracleClawbackReceivableStore;
import ga.comm.infra.store.OracleCommCalcStore;
import ga.comm.infra.store.OracleDeferralScheduleStore;
import ga.comm.infra.store.OracleInboundStatementStore;
import ga.comm.infra.store.OracleLimitLedgerStore;
import ga.comm.infra.store.OraclePolicyEventStore;
import ga.comm.infra.store.OracleSettleCloseStore;
import ga.comm.limit.LimitLedgerStore;
import ga.comm.rule.RuleRepository;
import ga.comm.rule.admin.CommRateAdminStore;
import ga.comm.rule.admin.RateApprovalService;
import ga.comm.settlement.AdjustmentService;
import ga.comm.settlement.AgentSettlementStore;
import ga.comm.settlement.CloseReport;
import ga.comm.settlement.SettleCloseStore;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.type.JdbcType;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.function.Supplier;

/**
 * Oracle 영속성 조립 (설계서 §4.1 commission-infra):
 * MyBatis(SqlSessionTemplate) + Spring 트랜잭션(DataSourceTransactionManager)으로
 * 각 Store 포트의 Oracle 어댑터를 구성한다.
 *
 * <p>트랜잭션 경계: {@link #inTx}가 설계서 §4.2의 [3.5]~[5.5](락 획득→게이트→저장→훅 전기)를
 * 하나의 단위로 묶는다 — 계산 호출부(배치/서비스)는 반드시 이 경계 안에서
 * {@code CommissionCalculator.process}를 실행해야 한다 (§6.1.6).
 * SqlSessionTemplate은 스프링 트랜잭션에 참여하며, 락 해제는 커밋/롤백 시점이다.
 */
public final class OraclePersistence {

    private final DataSourceTransactionManager txManager;
    private final TransactionTemplate txTemplate;
    private final SqlSessionTemplate session;

    public OraclePersistence(DataSource dataSource) {
        this.txManager = new DataSourceTransactionManager(dataSource);
        this.txTemplate = new TransactionTemplate(txManager);

        Environment environment =
                new Environment("oracle", new SpringManagedTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setJdbcTypeForNull(JdbcType.NULL); // Oracle: null 파라미터 바인딩 규약
        configuration.addMapper(PolicyEventMapper.class);
        configuration.addMapper(CommCalcMapper.class);
        configuration.addMapper(LimitLedgerMapper.class);
        configuration.addMapper(DeferralScheduleMapper.class);
        configuration.addMapper(ClawbackReceivableMapper.class);
        configuration.addMapper(SettleCloseMapper.class);
        configuration.addMapper(AgentSettlementMapper.class);
        configuration.addMapper(AdjustmentMapper.class);
        configuration.addMapper(InboundStatementMapper.class);
        configuration.addMapper(RuleQueryMapper.class);
        configuration.addMapper(CommRateAdminMapper.class);
        configuration.addMapper(ga.comm.infra.mapper.IncentiveAdminMapper.class);
        configuration.addMapper(AgentDirectoryMapper.class);
        configuration.addMapper(CloseReportMapper.class);
        configuration.addMapper(ga.comm.infra.mapper.DisclosureGradeMapper.class);

        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        this.session = new SqlSessionTemplate(factory);
    }

    public TransactionTemplate txTemplate() {
        return txTemplate;
    }

    public DataSourceTransactionManager txManager() {
        return txManager;
    }

    /** §4.2 [3.5]~[5.5]를 하나의 트랜잭션으로 — 계산·전기·상태 갱신의 표준 경계. */
    public <T> T inTx(Supplier<T> work) {
        return txTemplate.execute(status -> work.get());
    }

    public void inTx(Runnable work) {
        inTx(() -> {
            work.run();
            return null;
        });
    }

    public PolicyEventStore policyEventStore() {
        return new OraclePolicyEventStore(session.getMapper(PolicyEventMapper.class));
    }

    public CommCalcStore commCalcStore() {
        return new OracleCommCalcStore(session.getMapper(CommCalcMapper.class));
    }

    public LimitLedgerStore limitLedgerStore() {
        return new OracleLimitLedgerStore(session.getMapper(LimitLedgerMapper.class));
    }

    public DeferralScheduleStore deferralScheduleStore() {
        return new OracleDeferralScheduleStore(session.getMapper(DeferralScheduleMapper.class));
    }

    public ClawbackReceivableStore clawbackReceivableStore() {
        return new OracleClawbackReceivableStore(session.getMapper(ClawbackReceivableMapper.class));
    }

    public SettleCloseStore settleCloseStore() {
        return new OracleSettleCloseStore(session.getMapper(SettleCloseMapper.class));
    }

    public AgentSettlementStore agentSettlementStore() {
        return new OracleAgentSettlementStore(session.getMapper(AgentSettlementMapper.class));
    }

    public AdjustmentService.AdjustmentStore adjustmentStore() {
        return new OracleAdjustmentStore(session.getMapper(AdjustmentMapper.class));
    }

    public InboundStatementStore inboundStatementStore() {
        return new OracleInboundStatementStore(session.getMapper(InboundStatementMapper.class));
    }

    public RuleRepository ruleRepository() {
        return new OracleRuleRepository(session.getMapper(RuleQueryMapper.class));
    }

    public CommRateAdminStore commRateAdminStore() {
        return new OracleCommRateAdminStore(session.getMapper(CommRateAdminMapper.class));
    }

    public ga.comm.rule.admin.IncentiveAdminStore incentiveAdminStore() {
        return new ga.comm.infra.store.OracleIncentiveAdminStore(
                session.getMapper(ga.comm.infra.mapper.IncentiveAdminMapper.class));
    }

    public ga.comm.rule.IncentiveRepository incentiveRepository() {
        return new ga.comm.infra.store.OracleIncentiveRepository(
                session.getMapper(ga.comm.infra.mapper.IncentiveAdminMapper.class));
    }

    public AgentDirectory agentDirectory() {
        return new OracleAgentDirectory(session.getMapper(AgentDirectoryMapper.class));
    }

    // ---- 비교설명 등급·순위 (Phase E3) ----

    public ga.comm.disclosure.grade.policy.DisclosurePolicyRepository disclosurePolicyRepository() {
        return new ga.comm.infra.store.OracleDisclosurePolicyRepository(
                session.getMapper(ga.comm.infra.mapper.DisclosureGradeMapper.class));
    }

    public ga.comm.disclosure.grade.group.ProductGroupDirectory productGroupDirectory() {
        return new ga.comm.infra.store.OracleProductGroupDirectory(
                session.getMapper(ga.comm.infra.mapper.DisclosureGradeMapper.class));
    }

    public ga.comm.disclosure.grade.measure.SalesRateLedger salesRateLedger() {
        return new ga.comm.infra.store.OracleSalesRateLedger(ruleRepository(),
                session.getMapper(ga.comm.infra.mapper.DisclosureGradeMapper.class));
    }

    public ga.comm.disclosure.grade.snapshot.GradeSnapshotStore gradeSnapshotStore() {
        return new ga.comm.infra.store.OracleGradeSnapshotStore(
                session.getMapper(ga.comm.infra.mapper.DisclosureGradeMapper.class));
    }

    /** 마감 리포트 3종 (§7 Phase 11) — 룰 완결성 / MAXVALUE 파티션 적재 / 승인 경합 감지. */
    public java.util.List<CloseReport> closeReports() {
        return OracleCloseReports.all(session.getMapper(CloseReportMapper.class), 10);
    }

    /** 승인 동시성 러너(§6.6) — 인덱스 위반을 정상 경합으로 취급해 재시도/거부한다. */
    public OracleRateApprovalRunner rateApprovalRunner() {
        return new OracleRateApprovalRunner(txTemplate,
                new RateApprovalService(commRateAdminStore()));
    }

    /** 시책 승인 동시성 러너(§6.6) — 요율과 동형, lock-then-revalidate 재시도. */
    public ga.comm.infra.store.OracleIncentiveApprovalRunner incentiveApprovalRunner() {
        return new ga.comm.infra.store.OracleIncentiveApprovalRunner(txTemplate,
                new ga.comm.rule.admin.IncentiveApprovalService(incentiveAdminStore()));
    }
}
