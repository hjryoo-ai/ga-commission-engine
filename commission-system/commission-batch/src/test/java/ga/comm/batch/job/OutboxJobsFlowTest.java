package ga.comm.batch.job;

import ga.comm.batch.BatchRequestRunner;
import ga.comm.batch.BatchTestSupport;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.type.EventType;
import ga.comm.outbox.OutboxSequencer;
import ga.comm.outbox.feed.AgentMasterReader;
import ga.comm.outbox.feed.AgentSnapshotPublisher;
import ga.comm.outbox.fixture.InMemoryAgentMasterReader;
import ga.comm.outbox.fixture.InMemoryOutboxStore;
import ga.comm.outbox.fixture.ValidatingOutboxStore;
import ga.comm.outbox.publish.OutboxPublisher;
import ga.comm.outbox.publish.PortalPayloads;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** 아웃박스 잡 둘(E1 P8) — 순번 부여 잡은 미부여 행을 N건씩 빈틈 없이, AgentSnapshot 잡은 설계사마다 한 트랜잭션·실패 설계사 격리. */
class OutboxJobsFlowTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-05T09:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final BatchTestSupport.Env env = BatchTestSupport.env();
    private final InMemoryOutboxStore outbox = new InMemoryOutboxStore();
    private final OutboxPublisher publisher = new OutboxPublisher(new ValidatingOutboxStore(outbox), CLOCK);
    private final TransactionTemplate itemTx = new TransactionTemplate(new ResourcelessTransactionManager());

    @Test
    void 순번_부여_잡은_전부_붙일_때까지_N건씩() {
        for (int i = 0; i < 5; i++) {
            var e = EventFixtures.terminal(EventType.LAPSE, new PolicyNo("POL-" + i), EventFixtures.CONTRACT_DATE,
                    LocalDate.of(2026, 9, 10), 2);
            publisher.publish("PolicyEventObserved", "POLICY", "POL-" + i, PortalPayloads.policyEventObserved(e).orElseThrow());
        }
        Job job = OutboxSequenceJobFactory.job(env.runtime().jobRepository(), itemTx, new OutboxSequencer(outbox, 2));

        BatchRequestRunner.Outcome outcome = env.runner().submit(job,
                new JobParametersBuilder().addString("request_id", "SEQ-1").toJobParameters());

        assertThat(outcome.disposition()).isEqualTo(BatchRequestRunner.Disposition.COMPLETED);
        assertThat(outbox.headSeq()).isEqualTo(5);
        assertThat(outbox.readAssignedAfter(0, 10)).extracting(r -> r.seq()).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(outcome.execution().getStepExecutions().iterator().next().getExecutionContext()
                .getInt(OutboxSequenceJobFactory.CTX_ASSIGNED)).isEqualTo(5);
    }

    @Test
    void 스냅샷_잡은_설계사마다_발행하고_소속_없는_설계사는_격리한다() {
        InMemoryAgentMasterReader masters = new InMemoryAgentMasterReader()
                .with(new AgentMasterReader.AgentMaster("A-1001", "설계사A", LocalDate.of(2024, 3, 1), null))
                .with(new AgentMasterReader.AgentMaster("A-9999", "소속없음", LocalDate.of(2024, 3, 1), null));
        Job job = OutboxAgentSnapshotJobFactory.job(env.runtime().jobRepository(), itemTx,
                new AgentSnapshotPublisher(masters, CalcTestHarness.standardDirectory(), publisher));
        JobParameters params = new JobParametersBuilder().addString("as_of", "2026-09-01").addString("request_id", "BOOT-1")
                .toJobParameters();

        BatchRequestRunner.Outcome outcome = env.runner().submit(job, params);

        assertThat(outcome.disposition()).isEqualTo(BatchRequestRunner.Disposition.COMPLETED);
        assertThat(outcome.execution().getExitStatus().getExitCode()).isEqualTo(PayoutRunJobFactory.EXIT_WITH_SKIPS);
        assertThat(outcome.execution().getExecutionContext().getString(OutboxAgentSnapshotJobFactory.CTX_ISOLATED)).isEqualTo("A-9999");
        outbox.assignPending(10);
        assertThat(outbox.readAssignedAfter(0, 10)).extracting(r -> r.event().aggregateId()).containsExactly("A-1001");
        // 같은 요청 재제출은 no-op(JobInstance dedup)
        assertThat(env.runner().submit(job, params).disposition()).isEqualTo(BatchRequestRunner.Disposition.DEDUP_NOOP);
    }
}
