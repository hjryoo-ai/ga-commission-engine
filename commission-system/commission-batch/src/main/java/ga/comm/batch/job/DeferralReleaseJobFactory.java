package ga.comm.batch.job;

import ga.comm.deferral.DeferralReleaseService;
import ga.comm.domain.time.CloseYm;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 분급 도래 잡 (설계서 §6.2) — 파라미터: close_ym(식별).
 *
 * <p>마감 훅과 같은 서비스 경로를 단독 잡으로도 실행할 수 있게 한다(마감 전 선반영 등).
 * 도래분 RELEASE는 상태 전이가 재실행을 자연 차단하므로(SCHEDULED→RELEASED) 단일 업무
 * 트랜잭션·전체 중단으로 충분하다 — 실패 시 전체 롤백 후 재시작하면 처음부터 안전하게 반복된다.
 */
public final class DeferralReleaseJobFactory {

    public static final String JOB_NAME = "deferralReleaseJob";
    public static final String CTX_SUMMARY = "deferralRelease.summary";

    private DeferralReleaseJobFactory() {
    }

    public static Job job(JobRepository jobRepository, PlatformTransactionManager businessTx,
                          DeferralReleaseService releaseService) {
        Step step = new StepBuilder("deferralReleaseStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    JobParameters params = contribution.getStepExecution().getJobParameters();
                    CloseYm ym = CloseYm.of(MonthCloseJobFactory.required(
                            params.getString("close_ym"), "close_ym"));
                    DeferralReleaseService.ReleaseResult result = releaseService.release(ym);
                    contribution.getStepExecution().getJobExecution().getExecutionContext()
                            .putString(CTX_SUMMARY, "지급 투입 " + result.released().size()
                                    + "건, 보류(HELD) " + result.held() + "건");
                    return RepeatStatus.FINISHED;
                }, businessTx)
                .build();
        return new JobBuilder(JOB_NAME, jobRepository).start(step).build();
    }
}
