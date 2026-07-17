package ga.comm.batch.job;

import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.revision.RevisionService;
import ga.comm.calc.store.PolicyEventStore;
import ga.comm.domain.event.PolicyEvent;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
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
 * 재계산 잡 (설계서 §6.4) — 파라미터: request_id(식별 = §6.4 배치 계층 dedup 키),
 * event_ids CSV·reason(비식별 — 요청 ID가 작업을 명명한다. 같은 request_id 재제출은 no-op).
 *
 * <p><b>항목 단위 트랜잭션 ([3.5]~[5.5] 원자성, §6.1.6)</b>: 스텝 트랜잭션은 Resourceless로
 * 두고 이벤트 1건의 reversal+rebook을 태스크릿 반복 1회 = 업무 트랜잭션 1개로 닫는다.
 * 청크 크기와 무관하게 한도 원장 락은 해당 이벤트 처리 동안만 유지된다 — 여러 계산을 한
 * 트랜잭션에 묶으면 락 보유 구간이 길어져 대기·교착이 커지는 문제를 구조적으로 차단.
 *
 * <p>진행 오프셋은 반복마다 스텝 실행 컨텍스트에 저장되어 재시작 시 그 지점부터 계속한다.
 * 오프셋이 유실돼 같은 이벤트를 재처리해도 rebook은 값 멱등(§6.4)이라 순액이 변하지 않는다.
 * 실패 기준: 업무 예외(RuntimeException) = 항목 격리 후 계속(격리 목록 리포트),
 * 인프라 장애(Error) = 전체 중단 → 같은 request_id 재제출로 재시작.
 */
public final class RecalcJobFactory {

    public static final String JOB_NAME = "recalcJob";
    public static final String CTX_INDEX = "recalc.nextIndex";
    public static final String CTX_DONE = "recalc.doneCount";
    public static final String CTX_ISOLATED = "recalc.isolatedEvents";
    public static final String EXIT_WITH_SKIPS = "COMPLETED_WITH_SKIPS";

    private RecalcJobFactory() {
    }

    public static Job job(JobRepository jobRepository, TransactionTemplate itemTx,
                          RevisionService revisionService, PolicyEventStore eventStore,
                          CommissionCalculator calculator) {
        Step step = new StepBuilder("recalcStep", jobRepository)
                .tasklet(new RecalcTasklet(itemTx, revisionService, eventStore, calculator),
                        new ResourcelessTransactionManager())
                .build();
        return new JobBuilder(JOB_NAME, jobRepository).start(step).build();
    }

    static final class RecalcTasklet implements Tasklet {

        private final TransactionTemplate itemTx;
        private final RevisionService revisionService;
        private final PolicyEventStore eventStore;
        private final CommissionCalculator calculator;

        RecalcTasklet(TransactionTemplate itemTx, RevisionService revisionService,
                      PolicyEventStore eventStore, CommissionCalculator calculator) {
            this.itemTx = Objects.requireNonNull(itemTx);
            this.revisionService = Objects.requireNonNull(revisionService);
            this.eventStore = Objects.requireNonNull(eventStore);
            this.calculator = Objects.requireNonNull(calculator);
        }

        @Override
        public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
            JobParameters params = contribution.getStepExecution().getJobParameters();
            MonthCloseJobFactory.required(params.getString("request_id"), "request_id");
            String[] eventIds = MonthCloseJobFactory.required(
                    params.getString("event_ids"), "event_ids").split(",");
            String reason = params.getString("reason", "재계산 요청 "
                    + params.getString("request_id"));

            ExecutionContext ctx = contribution.getStepExecution().getExecutionContext();
            int index = ctx.getInt(CTX_INDEX, 0);
            if (index >= eventIds.length) {
                String isolated = ctx.getString(CTX_ISOLATED, "");
                if (!isolated.isEmpty()) {
                    contribution.getStepExecution().getJobExecution().getExecutionContext()
                            .putString(CTX_ISOLATED, isolated);
                    contribution.getStepExecution().setExitStatus(
                            new ExitStatus(EXIT_WITH_SKIPS, "격리 이벤트: " + isolated));
                }
                return RepeatStatus.FINISHED;
            }

            long eventId = Long.parseLong(eventIds[index].trim());
            try {
                // 이벤트 1건 = 업무 트랜잭션 1개: [3.5] 락 → [4] 게이트 → [5] 저장 → [5.5] 전기
                itemTx.execute(status -> {
                    PolicyEvent event = eventStore.findById(eventId).orElseThrow(
                            () -> new IllegalStateException("존재하지 않는 이벤트: " + eventId));
                    return revisionService.rebookEvent(event, calculator, reason);
                });
                ctx.putInt(CTX_DONE, ctx.getInt(CTX_DONE, 0) + 1);
            } catch (RuntimeException businessFailure) {
                String isolated = ctx.getString(CTX_ISOLATED, "");
                ctx.putString(CTX_ISOLATED, isolated.isEmpty()
                        ? eventId + ":" + businessFailure.getMessage()
                        : isolated + "," + eventId + ":" + businessFailure.getMessage());
            }
            ctx.putInt(CTX_INDEX, index + 1);
            return RepeatStatus.CONTINUABLE;
        }
    }
}
