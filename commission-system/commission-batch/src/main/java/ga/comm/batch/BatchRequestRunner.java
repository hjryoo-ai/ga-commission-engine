package ga.comm.batch;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;

import java.util.Objects;

/**
 * 배치 요청 제출기 — §6.4 멱등의 배치 계층(요청 ID dedup)을 구현한다.
 *
 * <ul>
 *   <li><b>같은 요청 재제출 = no-op</b>: (잡 이름 × 식별 파라미터)의 완료 인스턴스가 있으면
 *       실행하지 않고 {@link Disposition#DEDUP_NOOP}으로 종결 이력을 돌려준다.</li>
 *   <li><b>실패 인스턴스 재제출 = 재시작</b>: Spring Batch 재시작 시맨틱을 그대로 쓴다 —
 *       완료된 스텝은 건너뛰고 실패 지점부터 계속한다.</li>
 * </ul>
 */
public final class BatchRequestRunner {

    public enum Disposition {
        /** 이번 제출로 실행되어 완료. */
        COMPLETED,
        /** 같은 요청이 이미 완료되어 있어 실행하지 않음 (§6.4 배치 계층 멱등). */
        DEDUP_NOOP,
        /** 실행됐으나 실패 — 같은 파라미터 재제출 시 재시작된다. */
        FAILED
    }

    public record Outcome(Disposition disposition, JobExecution execution) {
    }

    private final JobRepository jobRepository;
    private final JobLauncher jobLauncher;

    public BatchRequestRunner(JobRepository jobRepository, JobLauncher jobLauncher) {
        this.jobRepository = Objects.requireNonNull(jobRepository);
        this.jobLauncher = Objects.requireNonNull(jobLauncher);
    }

    public Outcome submit(Job job, JobParameters params) {
        JobExecution last = jobRepository.getLastJobExecution(job.getName(), params);
        if (last != null && last.getStatus() == BatchStatus.COMPLETED) {
            return new Outcome(Disposition.DEDUP_NOOP, last);
        }
        try {
            JobExecution execution = jobLauncher.run(job, params);
            return new Outcome(execution.getStatus() == BatchStatus.COMPLETED
                    ? Disposition.COMPLETED : Disposition.FAILED, execution);
        } catch (JobInstanceAlreadyCompleteException raced) {
            return new Outcome(Disposition.DEDUP_NOOP,
                    jobRepository.getLastJobExecution(job.getName(), params));
        } catch (org.springframework.batch.core.repository.JobExecutionAlreadyRunningException
                 | org.springframework.batch.core.repository.JobRestartException
                 | org.springframework.batch.core.JobParametersInvalidException e) {
            throw new IllegalStateException("배치 제출 실패: " + job.getName(), e);
        }
    }
}
