package ga.comm.batch.job;

import ga.comm.outbox.feed.AgentSnapshotPublisher;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * AgentSnapshot 부트스트랩 잡(D-8, E1 P8·P10) — 파라미터 {@code as_of}(기준일, 식별)·{@code request_id}(식별 — 같은 기준일을 다시 내보낼 때).
 * 설계사 1명 = 업무 트랜잭션 1개. 실패한 설계사(소속 없음 등 계약에 담을 수 없는 값)는 격리하고 계속, 끝에 {@code COMPLETED_WITH_SKIPS}.
 * 재시작은 실행 컨텍스트의 위치에서 이어진다.
 */
public final class OutboxAgentSnapshotJobFactory {

    public static final String JOB_NAME = "outboxAgentSnapshotJob";
    public static final String CTX_POSITION = "agentSnapshot.position";
    public static final String CTX_PUBLISHED = "agentSnapshot.publishedCount";
    public static final String CTX_ISOLATED = "agentSnapshot.isolatedAgents";

    private OutboxAgentSnapshotJobFactory() {
    }

    public static Job job(JobRepository jobRepository, TransactionTemplate itemTx, AgentSnapshotPublisher publisher) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(new StepBuilder("outboxAgentSnapshotStep", jobRepository)
                        .tasklet(new SnapshotTasklet(itemTx, publisher), new ResourcelessTransactionManager())
                        .build())
                .build();
    }

    static final class SnapshotTasklet implements Tasklet {

        private final TransactionTemplate itemTx;
        private final AgentSnapshotPublisher publisher;

        SnapshotTasklet(TransactionTemplate itemTx, AgentSnapshotPublisher publisher) {
            this.itemTx = Objects.requireNonNull(itemTx);
            this.publisher = Objects.requireNonNull(publisher);
        }

        @Override
        public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
            JobParameters params = contribution.getStepExecution().getJobParameters();
            LocalDate asOf = LocalDate.parse(MonthCloseJobFactory.required(params.getString("as_of"), "as_of"));
            ExecutionContext ctx = contribution.getStepExecution().getExecutionContext();
            List<String> agents = itemTx.execute(status -> publisher.agentIds());
            int position = ctx.getInt(CTX_POSITION, 0);
            if (agents == null || position >= agents.size()) {
                String isolated = ctx.getString(CTX_ISOLATED, "");
                if (!isolated.isEmpty()) {
                    contribution.getStepExecution().getJobExecution().getExecutionContext().putString(CTX_ISOLATED, isolated);
                    contribution.getStepExecution().setExitStatus(new ExitStatus(PayoutRunJobFactory.EXIT_WITH_SKIPS,
                            "격리 설계사: " + isolated));
                }
                return RepeatStatus.FINISHED;
            }
            String agentId = agents.get(position);
            try {
                itemTx.executeWithoutResult(status -> publisher.publish(agentId, asOf));
                ctx.putInt(CTX_PUBLISHED, ctx.getInt(CTX_PUBLISHED, 0) + 1);
            } catch (RuntimeException failure) {
                Set<String> isolated = new LinkedHashSet<>();
                String stored = ctx.getString(CTX_ISOLATED, "");
                if (!stored.isEmpty()) {
                    isolated.addAll(List.of(stored.split(",")));
                }
                isolated.add(agentId);
                ctx.putString(CTX_ISOLATED, String.join(",", isolated));
            }
            ctx.putInt(CTX_POSITION, position + 1);
            return RepeatStatus.CONTINUABLE;
        }
    }
}
