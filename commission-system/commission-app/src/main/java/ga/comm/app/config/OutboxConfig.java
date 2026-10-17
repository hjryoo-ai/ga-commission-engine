package ga.comm.app.config;

import ga.comm.app.security.InstanceProperties;
import ga.comm.batch.job.OutboxAgentSnapshotJobFactory;
import ga.comm.batch.job.OutboxSequenceJobFactory;
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
}
