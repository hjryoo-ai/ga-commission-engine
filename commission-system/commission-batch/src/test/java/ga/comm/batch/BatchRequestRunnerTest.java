package ga.comm.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 배치 요청 dedup·재시작 (설계서 §6.4 배치 계층 멱등). */
class BatchRequestRunnerTest {

    private final BatchTestSupport.Env env = BatchTestSupport.env();

    private Job countingJob(String name, AtomicInteger counter) {
        return new JobBuilder(name, env.runtime().jobRepository())
                .start(new StepBuilder(name + "Step", env.runtime().jobRepository())
                        .tasklet((contribution, chunkContext) -> {
                            counter.incrementAndGet();
                            return RepeatStatus.FINISHED;
                        }, new ResourcelessTransactionManager())
                        .build())
                .build();
    }

    @Test
    @DisplayName("같은 요청(식별 파라미터) 재제출은 no-op — 실행 횟수가 늘지 않는다")
    void 같은_요청_재제출은_noop() {
        AtomicInteger counter = new AtomicInteger();
        Job job = countingJob("dedupJob", counter);
        var params = BatchTestSupport.recalcParams("REQ-1", "1,2", "r");

        assertThat(env.runner().submit(job, params).disposition())
                .isEqualTo(BatchRequestRunner.Disposition.COMPLETED);
        assertThat(env.runner().submit(job, params).disposition())
                .isEqualTo(BatchRequestRunner.Disposition.DEDUP_NOOP);
        assertThat(env.runner().submit(job, params).disposition())
                .isEqualTo(BatchRequestRunner.Disposition.DEDUP_NOOP);
        assertThat(counter.get()).isEqualTo(1);

        // 다른 요청 ID는 새 인스턴스로 실행된다
        assertThat(env.runner().submit(job, BatchTestSupport.recalcParams("REQ-2", "1,2", "r"))
                .disposition()).isEqualTo(BatchRequestRunner.Disposition.COMPLETED);
        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("실패 인스턴스의 같은 파라미터 재제출은 재시작이다 (dedup 아님)")
    void 실패_인스턴스는_재시작된다() {
        AtomicBoolean bombArmed = new AtomicBoolean(true);
        AtomicInteger completions = new AtomicInteger();
        Job job = new JobBuilder("restartJob", env.runtime().jobRepository())
                .start(new StepBuilder("restartStep", env.runtime().jobRepository())
                        .tasklet((contribution, chunkContext) -> {
                            if (bombArmed.getAndSet(false)) {
                                throw new IllegalStateException("1차 실행 실패(주입)");
                            }
                            completions.incrementAndGet();
                            return RepeatStatus.FINISHED;
                        }, new ResourcelessTransactionManager())
                        .build())
                .build();
        var params = BatchTestSupport.closeParams("202608", "정산담당", false, null);

        assertThat(env.runner().submit(job, params).disposition())
                .isEqualTo(BatchRequestRunner.Disposition.FAILED);
        assertThat(env.runner().submit(job, params).disposition())
                .isEqualTo(BatchRequestRunner.Disposition.COMPLETED);
        assertThat(completions.get()).isEqualTo(1);

        // 재시작 완료 후 재제출은 dedup
        assertThat(env.runner().submit(job, params).disposition())
                .isEqualTo(BatchRequestRunner.Disposition.DEDUP_NOOP);
    }
}
