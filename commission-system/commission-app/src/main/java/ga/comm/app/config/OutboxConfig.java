package ga.comm.app.config;

import ga.comm.app.security.InstanceProperties;
import ga.comm.batch.job.OutboxAgentSnapshotJobFactory;
import ga.comm.batch.job.OutboxSequenceJobFactory;
import ga.comm.batch.job.StatementEstimateJobFactory;
import ga.comm.calc.store.CloseStatusProvider;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.clawback.ClawbackReceivableStore;
import ga.comm.deferral.DeferralScheduleStore;
import ga.comm.deferral.PolicyStatusProvider;
import ga.comm.outbox.publish.EstimatePublisher;
import ga.comm.settlement.AdjustmentService;
import ga.comm.settlement.SettleCloseStore;
import ga.comm.settlement.WithholdingTaxPolicy;
import ga.comm.settlement.estimate.EstimateReads;
import ga.comm.settlement.estimate.StatementEstimator;
import ga.comm.calc.recipient.AgentDirectory;
import ga.comm.infra.OraclePersistence;
import ga.comm.outbox.OutboxSequencer;
import ga.comm.outbox.OutboxStore;
import ga.comm.outbox.feed.AgentSnapshotPublisher;
import ga.comm.outbox.feed.EventFeed;
import ga.comm.outbox.publish.OutboxPublisher;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.function.Supplier;

/**
 * 이벤트 피드·순번 부여·AgentSnapshot 부트스트랩 배선(Phase E1, 설계서 §6.8). 피드는 호출마다 쓰기 트랜잭션(부여, 최대
 * {@code app.outbox.assign-batch-size}건)과 읽기 전용 트랜잭션(읽기)을 따로 연다(P8). 응답 {@code tenantId}는 인스턴스 테넌트({@code app.tenant-id}).
 */
@Configuration(proxyBeanMethods = false)
public class OutboxConfig {

    @Bean
    public OutboxSequencer outboxSequencer(OutboxStore outboxStore,
                                           @Value("${app.outbox.assign-batch-size:500}") int assignBatchSize) {
        return new OutboxSequencer(outboxStore, assignBatchSize);
    }

    @Bean
    public EventFeed eventFeed(OutboxStore outboxStore, OutboxSequencer sequencer, OraclePersistence p, InstanceProperties instance) {
        TransactionTemplate readOnly = new TransactionTemplate(p.txManager());
        readOnly.setReadOnly(true);
        return new EventFeed(outboxStore, sequencer, tx(p.txTemplate()), tx(readOnly), instance.requiredTenantId());
    }

    private static EventFeed.Tx tx(TransactionTemplate template) {
        return new EventFeed.Tx() {
            @Override
            public <T> T run(Supplier<T> work) {
                return template.execute(status -> work.get());
            }
        };
    }

    @Bean
    public AgentSnapshotPublisher agentSnapshotPublisher(OraclePersistence p, AgentDirectory agentDirectory, OutboxPublisher publisher) {
        return new AgentSnapshotPublisher(p.agentMasterReader(), agentDirectory, publisher);
    }

    @Bean
    public Job outboxSequenceJob(JobRepository jobRepository, TransactionTemplate itemTx, OutboxSequencer sequencer) {
        return OutboxSequenceJobFactory.job(jobRepository, itemTx, sequencer);
    }

    @Bean
    public Job outboxAgentSnapshotJob(JobRepository jobRepository, TransactionTemplate itemTx, AgentSnapshotPublisher publisher) {
        return OutboxAgentSnapshotJobFactory.job(jobRepository, itemTx, publisher);
    }

    /**
     * 잠정 명세 산출기(E2 ①) — 업무 저장소 빈의 <b>조회 메서드 참조</b>만 받는다(E2 승인 Q1: 쓰기 메서드가 손에 없음을 컴파일로 보장). 유지 판정기·
     * 원천세 정책은 마감 훅·지급 런과 같은 빈(P1·P9).
     */
    @Bean
    public StatementEstimator statementEstimator(EstimateReads estimateReads, WithholdingTaxPolicy withholdingTaxPolicy) {
        return new StatementEstimator(estimateReads, withholdingTaxPolicy);
    }

    /** 잠정 명세 산출기·시뮬레이터(E2 ②의 귀속월 전후 순액)가 함께 쓰는 읽기 함수 묶음. */
    @Bean
    public EstimateReads estimateReads(CommCalcStore calcStore, DeferralScheduleStore scheduleStore, PolicyStatusProvider policyStatus,
                                       ClawbackReceivableStore receivableStore, AdjustmentService.AdjustmentStore adjustmentStore,
                                       SettleCloseStore settleCloseStore) {
        return new EstimateReads(calcStore::findByCloseYm, calcStore::findById, scheduleStore::findDue, policyStatus,
                receivableStore::findOffsettable, adjustmentStore::findByCloseYm, settleCloseStore::stateOf);
    }

    @Bean
    public EstimatePublisher estimatePublisher(OutboxPublisher publisher) {
        return new EstimatePublisher(publisher);
    }

    /** 잠정 명세 잡 — 부여·적재는 항목 쓰기 트랜잭션, headSeq 읽기·산출은 읽기 전용 트랜잭션(E2 Q3). 야간 1회는 외부 cron(P5). */
    @Bean
    public Job statementEstimateJob(JobRepository jobRepository, TransactionTemplate itemTx, OraclePersistence p, OutboxSequencer sequencer,
                                    OutboxStore outboxStore, StatementEstimator estimator, EstimatePublisher estimatePublisher,
                                    CloseStatusProvider closeStatus, Clock clock) {
        TransactionTemplate readOnly = new TransactionTemplate(p.txManager());
        readOnly.setReadOnly(true);
        return StatementEstimateJobFactory.job(jobRepository, new StatementEstimateJobFactory.Wiring(itemTx, readOnly, sequencer, outboxStore,
                estimator, estimatePublisher, closeStatus, clock));
    }

    /** 명세 조회(E2 ③) — 읽기 전용 트랜잭션 하나에서 산출기(ESTIMATE) 또는 업무 표 + 발행 빌더(CONFIRMED, Q4). 발행하지 않는다. */
    @Bean
    public ga.comm.outbox.feed.StatementQuery statementQuery(StatementEstimator estimator, CommCalcStore calcStore,
                                                            AdjustmentService.AdjustmentStore adjustmentStore, SettleCloseStore settleCloseStore,
                                                            ga.comm.settlement.PayoutStatementStore payoutStatementStore, OraclePersistence p,
                                                            OutboxStore outboxStore, WithholdingTaxPolicy withholdingTaxPolicy, Clock clock) {
        TransactionTemplate readOnly = new TransactionTemplate(p.txManager());
        readOnly.setReadOnly(true);
        return new ga.comm.outbox.feed.StatementQuery(estimator, calcStore, adjustmentStore, settleCloseStore, payoutStatementStore,
                p.agentMasterReader(), outboxStore, tx(readOnly), withholdingTaxPolicy, clock);
    }
}
