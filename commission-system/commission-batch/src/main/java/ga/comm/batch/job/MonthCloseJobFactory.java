package ga.comm.batch.job;

import ga.comm.domain.time.CloseYm;
import ga.comm.settlement.CloseReport;
import ga.comm.settlement.MonthCloseService;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Objects;

/**
 * 월마감 잡 (설계서 §7 D-Day, §10 Phase 11).
 *
 * <p>구성: ① closeStep — 게이트(미처리 PENDING 차단)·훅·한도 검증·확정·CLOSED를 <b>하나의
 * 업무 트랜잭션</b>으로(마감은 월 단위 원자 — 실패 시 전체 중단·롤백·재시작),
 * ② closeReportStep — 안전망 리포트 3종(비차단, 발견 항목은 잡 실행 컨텍스트에 영속).
 *
 * <p>파라미터: close_ym(식별) / closed_by·force·force_reason(비식별 — 게이트 차단으로 실패한
 * 인스턴스를 강제 마감으로 재시작할 수 있다). 강제 마감 사유는 체크리스트와 함께
 * BATCH_JOB_EXECUTION_CONTEXT에 남는다(권한+사유 기록, §7).
 */
public final class MonthCloseJobFactory {

    public static final String JOB_NAME = "monthCloseJob";
    public static final String CTX_CHECKLIST = "close.checklist";
    public static final String CTX_REPORT_PREFIX = "closeReport.";
    public static final String CTX_REPORT_TOTAL = "closeReport.totalFindings";

    /** 게이트 차단 — 잡을 FAILED로 종결시켜 재시작(보완 후 또는 강제 마감) 대상으로 만든다. */
    public static class MonthCloseBlockedException extends RuntimeException {
        public MonthCloseBlockedException(String message) {
            super(message);
        }
    }

    private MonthCloseJobFactory() {
    }

    public static Job job(JobRepository jobRepository, PlatformTransactionManager businessTx,
                          MonthCloseService closeService, List<CloseReport> reports) {
        Objects.requireNonNull(closeService);
        List<CloseReport> closeReports = List.copyOf(reports);

        Step closeStep = new StepBuilder("closeStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    JobParameters params = contribution.getStepExecution().getJobParameters();
                    CloseYm ym = CloseYm.of(required(params.getString("close_ym"), "close_ym"));
                    String closedBy = required(params.getString("closed_by"), "closed_by");
                    boolean force = "Y".equals(params.getString("force"));
                    String forceReason = params.getString("force_reason");

                    MonthCloseService.CloseResult result =
                            closeService.close(ym, closedBy, force, force ? forceReason : null);

                    StringBuilder checklist = new StringBuilder();
                    for (MonthCloseService.CheckItem item : result.checklist()) {
                        checklist.append(item.passed() ? "PASS " : "FAIL ")
                                .append(item.name()).append(" — ").append(item.detail()).append('\n');
                    }
                    contribution.getStepExecution().getJobExecution().getExecutionContext()
                            .putString(CTX_CHECKLIST, checklist.toString());

                    if (!result.closed()) {
                        throw new MonthCloseBlockedException(
                                "마감 차단 (" + ym + "):\n" + checklist);
                    }
                    return RepeatStatus.FINISHED;
                }, businessTx)
                .build();

        Step reportStep = new StepBuilder("closeReportStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    JobParameters params = contribution.getStepExecution().getJobParameters();
                    CloseYm ym = CloseYm.of(required(params.getString("close_ym"), "close_ym"));
                    ExecutionContext jobCtx =
                            contribution.getStepExecution().getJobExecution().getExecutionContext();

                    int total = 0;
                    for (CloseReport report : closeReports) {
                        List<String> findings = report.findings(ym);
                        total += findings.size();
                        jobCtx.putString(CTX_REPORT_PREFIX + report.name(),
                                String.join("\n", findings));
                    }
                    jobCtx.putInt(CTX_REPORT_TOTAL, total);
                    return RepeatStatus.FINISHED;
                }, businessTx)
                .build();

        return new JobBuilder(JOB_NAME, jobRepository).start(closeStep).next(reportStep).build();
    }

    static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("잡 파라미터 누락: " + name);
        }
        return value;
    }
}
