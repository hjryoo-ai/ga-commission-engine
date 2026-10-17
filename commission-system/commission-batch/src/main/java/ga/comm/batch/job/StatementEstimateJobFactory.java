package ga.comm.batch.job;

import ga.comm.calc.store.CloseStatusProvider;
import ga.comm.domain.time.CloseYm;
import ga.comm.outbox.OutboxSequencer;
import ga.comm.outbox.OutboxStore;
import ga.comm.outbox.publish.EstimatePublisher;
import ga.comm.settlement.estimate.MonthClosedException;
import ga.comm.settlement.estimate.StatementEstimator;
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

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * 잠정 명세 잡(E2 ①, 설계서 §6.8) — 파라미터 {@code request_id}(식별), {@code close_ym}(선택 — 없으면 오늘의 귀속월). 엔진에 스케줄러가 없으므로
 * (E2 P5) 기본 주기(야간 1회)는 외부 cron이 이 잡을 기동한다. 순서(E2 Q3): ⓐ 미부여 행 순번 부여(쓰기 트랜잭션 반복, 0건까지)
 * ⓑ {@code basisSeq} = 그 뒤 최대 부여 순번(하한 — 그 순번까지의 사실은 전부 반영) ⓒ 산출(읽기 전용 트랜잭션, 잠금 없음 — C3)
 * ⓓ 설계사마다 쓰기 트랜잭션 하나로 {@code StatementEstimated} 적재(업무 표는 쓰지 않는다 — C1). 마감·마감 중 달이면 아무것도 내지 않고
 * {@code SKIPPED_CLOSED_MONTH}로 끝난다(그 달은 StatementConfirmed가 정본).
 */
public final class StatementEstimateJobFactory {

    public static final String JOB_NAME = "statementEstimateJob";
    public static final String CTX_PUBLISHED = "estimate.publishedCount";
    public static final String CTX_BASIS_SEQ = "estimate.basisSeq";
    public static final String EXIT_CLOSED = "SKIPPED_CLOSED_MONTH";

    /** 잡이 쓰는 것들(읽기 전용 트랜잭션은 산출과 headSeq 읽기에만). */
    public record Wiring(TransactionTemplate itemTx, TransactionTemplate readOnlyTx, OutboxSequencer sequencer, OutboxStore outbox,
                         StatementEstimator estimator, EstimatePublisher publisher, CloseStatusProvider closeStatus, Clock clock) {
        public Wiring {
            Objects.requireNonNull(itemTx);
            Objects.requireNonNull(readOnlyTx);
            Objects.requireNonNull(sequencer);
            Objects.requireNonNull(outbox);
            Objects.requireNonNull(estimator);
            Objects.requireNonNull(publisher);
            Objects.requireNonNull(closeStatus);
            Objects.requireNonNull(clock);
        }
    }

    private StatementEstimateJobFactory() {
    }

    public static Job job(JobRepository jobRepository, Wiring wiring) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(new StepBuilder("statementEstimateStep", jobRepository)
                        .tasklet(new EstimateTasklet(wiring), new ResourcelessTransactionManager())
                        .build())
                .build();
    }

    static final class EstimateTasklet implements Tasklet {

        private final Wiring w;

        EstimateTasklet(Wiring w) {
            this.w = w;
        }

        @Override
        public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
            JobParameters params = contribution.getStepExecution().getJobParameters();
            MonthCloseJobFactory.required(params.getString("request_id"), "request_id");
            String ymParam = params.getString("close_ym");
            CloseYm ym = ymParam == null || ymParam.isBlank()
                    ? w.closeStatus().attributionFor(CloseYm.from(LocalDate.now(w.clock())))
                    : CloseYm.of(ymParam);

            Integer assigned;
            do {
                assigned = w.itemTx().execute(status -> w.sequencer().assignOnce());
            } while (assigned != null && assigned > 0);
            long basisSeq = Objects.requireNonNull(w.readOnlyTx().execute(status -> w.outbox().headSeq()));
            OffsetDateTime asOf = OffsetDateTime.now(w.clock()).truncatedTo(ChronoUnit.SECONDS);

            List<StatementEstimator.Estimate> estimates;
            try {
                estimates = w.readOnlyTx().execute(status -> w.estimator().estimate(ym));
            } catch (MonthClosedException closed) {
                contribution.getStepExecution().setExitStatus(new ExitStatus(EXIT_CLOSED, "마감·마감 중 달: " + ym));
                return RepeatStatus.FINISHED;
            }
            int published = 0;
            for (StatementEstimator.Estimate e : Objects.requireNonNull(estimates)) {
                w.itemTx().executeWithoutResult(status -> w.publisher().publish(e, basisSeq, asOf));
                published++;
            }
            ExecutionContext ctx = contribution.getStepExecution().getExecutionContext();
            ctx.putInt(CTX_PUBLISHED, published);
            ctx.putLong(CTX_BASIS_SEQ, basisSeq);
            return RepeatStatus.FINISHED;
        }
    }
}
