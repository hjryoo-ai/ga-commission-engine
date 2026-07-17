package ga.comm.batch;

import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;

/**
 * 배치 흐름 테스트 지원 — 잡 저장소는 H2 + Spring Batch 동봉 스키마(자립 구성),
 * 업무 스토어는 인메모리 레퍼런스. 실 Oracle 검증(재시작 값 멱등성 등)은 infra IT의 몫이다.
 */
public final class BatchTestSupport {

    public record Env(BatchRuntime runtime, BatchRequestRunner runner) {
    }

    private BatchTestSupport() {
    }

    public static Env env() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:org/springframework/batch/core/schema-h2.sql")
                .generateUniqueName(true)
                .build();
        BatchRuntime runtime = new BatchRuntime(dataSource,
                new DataSourceTransactionManager(dataSource), "H2");
        return new Env(runtime, new BatchRequestRunner(runtime.jobRepository(), runtime.jobLauncher()));
    }

    public static JobParameters closeParams(String closeYm, String closedBy, boolean force, String reason) {
        JobParametersBuilder builder = new JobParametersBuilder()
                .addString("close_ym", closeYm)
                .addString("closed_by", closedBy, false)
                .addString("force", force ? "Y" : "N", false);
        if (reason != null) {
            builder.addString("force_reason", reason, false);
        }
        return builder.toJobParameters();
    }

    public static JobParameters payoutParams(String closeYm, int runSeq) {
        return new JobParametersBuilder()
                .addString("close_ym", closeYm)
                .addLong("run_seq", (long) runSeq)
                .toJobParameters();
    }

    public static JobParameters recalcParams(String requestId, String eventIdsCsv, String reason) {
        return new JobParametersBuilder()
                .addString("request_id", requestId)
                .addString("event_ids", eventIdsCsv, false)
                .addString("reason", reason, false)
                .toJobParameters();
    }
}
