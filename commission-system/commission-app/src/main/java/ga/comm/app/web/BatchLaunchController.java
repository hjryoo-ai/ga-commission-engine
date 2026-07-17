package ga.comm.app.web;

import ga.comm.batch.BatchRequestRunner;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 배치 잡 기동 (설계서 §10 Phase 16) — 웹 앱은 잡을 <b>자동 실행하지 않는다</b>(Spring Batch 자동설정
 * 제외). 잡은 오직 이 <b>보호된 엔드포인트(OPERATOR/ADMIN)</b>를 통해 <b>명시 파라미터로만</b> 기동한다.
 * 요청 재제출은 {@link BatchRequestRunner}가 JobInstance 기준으로 dedup한다(멱등).
 *
 * <p>대안 기동 방식(운영 선택): CLI(별도 실행 진입점)로도 같은 잡을 명시 파라미터로 기동할 수 있다 —
 * 스케줄러(사내 배치 오케스트레이터)가 CLI/엔드포인트 중 하나로 호출한다(README 운영 절). 기동 방식과
 * 무관하게 dedup·트랜잭션 경계는 동일하다.
 */
@RestController
@RequestMapping("/api/batch")
public class BatchLaunchController {

    private final BatchRequestRunner runner;
    private final Map<String, Job> jobs; // 빈 이름(monthCloseJob 등) → Job

    public BatchLaunchController(BatchRequestRunner runner, Map<String, Job> jobs) {
        this.runner = runner;
        this.jobs = jobs;
    }

    /** 명시 파라미터로 잡을 기동한다. 파라미터가 없으면 거부(자동/무인자 기동 금지). */
    @PostMapping("/{jobName}/run")
    public LaunchResponse run(@PathVariable String jobName,
                             @RequestBody Map<String, String> params) {
        Job job = jobs.get(jobName);
        if (job == null) {
            throw new IllegalArgumentException("알 수 없는 잡: " + jobName);
        }
        if (params == null || params.isEmpty()) {
            throw new IllegalArgumentException("잡은 명시 파라미터로만 기동합니다(무인자 기동 금지): " + jobName);
        }
        JobParametersBuilder builder = new JobParametersBuilder();
        params.forEach(builder::addString);
        JobParameters jobParameters = builder.toJobParameters();

        BatchRequestRunner.Outcome outcome = runner.submit(job, jobParameters);
        return new LaunchResponse(jobName, outcome.disposition().name());
    }

    public record LaunchResponse(String jobName, String disposition) {
    }
}
