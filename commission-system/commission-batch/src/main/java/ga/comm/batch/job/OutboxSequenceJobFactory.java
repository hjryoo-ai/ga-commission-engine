package ga.comm.batch.job;

import ga.comm.outbox.OutboxSequencer;
import org.springframework.batch.core.Job;
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

import java.util.Objects;

/**
 * 아웃박스 순번 부여 잡(설계서 §6.8, E1 P8) — 피드 호출의 lazy 부여와 같은 일({@link OutboxSequencer#assignOnce})을 배치로. 파라미터는
 * {@code request_id}(식별 — 기동 컨트롤러가 명시 파라미터를 요구한다). 반복 1회 = 단독 쓰기 트랜잭션 1개(최대 N건), 붙인 것이 0이면 끝난다.
 * 엔진에 스케줄러가 없으므로(E1 지시문 D-1 오류) 주기 실행은 외부 오케스트레이터 몫이고, 포털 폴링이 lazy 부여를 끌어간다.
 */
public final class OutboxSequenceJobFactory {

    public static final String JOB_NAME = "outboxSequenceJob";
    public static final String CTX_ASSIGNED = "outbox.assignedCount";

    private OutboxSequenceJobFactory() {
    }

    public static Job job(JobRepository jobRepository, TransactionTemplate itemTx, OutboxSequencer sequencer) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(new StepBuilder("outboxSequenceStep", jobRepository)
                        .tasklet(new SequenceTasklet(itemTx, sequencer), new ResourcelessTransactionManager())
                        .build())
                .build();
    }

    static final class SequenceTasklet implements Tasklet {

        private final TransactionTemplate itemTx;
        private final OutboxSequencer sequencer;

        SequenceTasklet(TransactionTemplate itemTx, OutboxSequencer sequencer) {
            this.itemTx = Objects.requireNonNull(itemTx);
            this.sequencer = Objects.requireNonNull(sequencer);
        }

        @Override
        public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
            MonthCloseJobFactory.required(contribution.getStepExecution().getJobParameters().getString("request_id"), "request_id");
            Integer assigned = itemTx.execute(status -> sequencer.assignOnce());
            int n = assigned == null ? 0 : assigned;
            ExecutionContext ctx = contribution.getStepExecution().getExecutionContext();
            ctx.putInt(CTX_ASSIGNED, ctx.getInt(CTX_ASSIGNED, 0) + n);
            return n == 0 ? RepeatStatus.FINISHED : RepeatStatus.CONTINUABLE;
        }
    }
}
