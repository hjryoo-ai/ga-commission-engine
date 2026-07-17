package ga.comm.batch.job;

import ga.comm.domain.time.CloseYm;
import ga.comm.settlement.PayoutService;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 지급 런 잡 (설계서 §7 D+1~, §11.12) — 파라미터: close_ym·run_seq(식별). 월 N회 반복 실행.
 *
 * <p><b>항목 단위 트랜잭션</b>: 스텝 트랜잭션은 Resourceless(no-op)로 두고, 수급자 1명의
 * 정산(상계·정산행·PAID 전이)을 태스크릿 반복 1회 = 업무 트랜잭션 1개로 닫는다.
 * 실패 수급자는 <b>격리 후 리포트</b>(업무 예외 = RuntimeException — 해당 수급자 작업만
 * 롤백하고 나머지는 계속). 인프라 장애(Error)는 전체 중단 → 재시작. 재실행·재시작은
 * 정산행 편입 여부로 자연 멱등(이미 정산된 수급자는 대상에서 빠진다).
 */
public final class PayoutRunJobFactory {

    public static final String JOB_NAME = "payoutRunJob";
    public static final String CTX_SETTLED = "payout.settledCount";
    public static final String CTX_ISOLATED = "payout.isolatedRecipients";
    public static final String CTX_INCOME_TAX = "payout.incomeTaxTotal";
    public static final String CTX_LOCAL_TAX = "payout.localTaxTotal";
    public static final String CTX_NET_PAY = "payout.netPayTotal";
    public static final String EXIT_WITH_SKIPS = "COMPLETED_WITH_SKIPS";

    private PayoutRunJobFactory() {
    }

    public static Job job(JobRepository jobRepository, TransactionTemplate itemTx,
                          PayoutService payoutService) {
        Step step = new StepBuilder("payoutRunStep", jobRepository)
                .tasklet(new PayoutRunTasklet(itemTx, payoutService),
                        new ResourcelessTransactionManager())
                .build();
        return new JobBuilder(JOB_NAME, jobRepository).start(step).build();
    }

    static final class PayoutRunTasklet implements Tasklet {

        private final TransactionTemplate itemTx;
        private final PayoutService payoutService;

        PayoutRunTasklet(TransactionTemplate itemTx, PayoutService payoutService) {
            this.itemTx = Objects.requireNonNull(itemTx);
            this.payoutService = Objects.requireNonNull(payoutService);
        }

        @Override
        public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
            JobParameters params = contribution.getStepExecution().getJobParameters();
            CloseYm ym = CloseYm.of(MonthCloseJobFactory.required(
                    params.getString("close_ym"), "close_ym"));
            Long runSeqParam = params.getLong("run_seq");
            if (runSeqParam == null) {
                throw new IllegalArgumentException("잡 파라미터 누락: run_seq");
            }
            int runSeq = runSeqParam.intValue();

            ExecutionContext ctx = contribution.getStepExecution().getExecutionContext();
            Set<String> isolated = readIsolated(ctx);

            List<PayoutService.RecipientKey> pending = itemTx.execute(status ->
                            payoutService.pendingRecipients(ym)).stream()
                    .filter(key -> !isolated.contains(keyOf(key)))
                    .toList();

            if (pending.isEmpty()) {
                if (!isolated.isEmpty()) {
                    contribution.getStepExecution().getJobExecution().getExecutionContext()
                            .putString(CTX_ISOLATED, String.join(",", isolated));
                    contribution.getStepExecution().setExitStatus(
                            new ExitStatus(EXIT_WITH_SKIPS, "격리 수급자: " + isolated));
                }
                return RepeatStatus.FINISHED;
            }

            PayoutService.RecipientKey key = pending.get(0);
            try {
                // 수급자 1명 = 업무 트랜잭션 1개: 상계·정산행·PAID 전이가 원자적으로 닫힌다
                PayoutService.PayoutStatement statement =
                        itemTx.execute(status -> payoutService.settleOne(ym, runSeq, key));
                if (statement != null) {
                    ctx.putInt(CTX_SETTLED, ctx.getInt(CTX_SETTLED, 0) + 1);
                    ctx.putLong(CTX_INCOME_TAX,
                            ctx.getLong(CTX_INCOME_TAX, 0) + statement.incomeTax().toLong());
                    ctx.putLong(CTX_LOCAL_TAX,
                            ctx.getLong(CTX_LOCAL_TAX, 0) + statement.localTax().toLong());
                    ctx.putLong(CTX_NET_PAY,
                            ctx.getLong(CTX_NET_PAY, 0) + statement.netPay().toLong());
                }
            } catch (RuntimeException businessFailure) {
                // 항목 격리: 이 수급자의 트랜잭션은 롤백됐다 — 기록 후 다음 수급자 계속
                isolated.add(keyOf(key));
                ctx.putString(CTX_ISOLATED, String.join(",", isolated));
            }
            return RepeatStatus.CONTINUABLE;
        }

        private static Set<String> readIsolated(ExecutionContext ctx) {
            String stored = ctx.getString(CTX_ISOLATED, "");
            Set<String> isolated = new LinkedHashSet<>();
            if (!stored.isEmpty()) {
                isolated.addAll(new ArrayList<>(List.of(stored.split(","))));
            }
            return isolated;
        }

        private static String keyOf(PayoutService.RecipientKey key) {
            return key.type().name() + ":" + key.id();
        }
    }
}
