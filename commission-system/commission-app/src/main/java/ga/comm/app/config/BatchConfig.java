package ga.comm.app.config;

import ga.comm.batch.BatchRequestRunner;
import ga.comm.batch.BatchRuntime;
import ga.comm.batch.job.DeferralReleaseJobFactory;
import ga.comm.batch.job.MonthCloseJobFactory;
import ga.comm.batch.job.PayoutRunJobFactory;
import ga.comm.batch.job.RecalcJobFactory;
import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.revision.RevisionService;
import ga.comm.calc.store.PolicyEventStore;
import ga.comm.deferral.DeferralReleaseService;
import ga.comm.settlement.CloseReport;
import ga.comm.settlement.MonthCloseService;
import ga.comm.settlement.PayoutService;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;

/**
 * 배치 배선 (Phase 16). Spring Batch 자동설정은 {@code CommissionApplication}에서 제외했고 —
 * 저장소·런처는 여기서 <b>수동 배선</b>한다({@link BatchRuntime}). Phase 11의 트랜잭션 구조가
 * 조립 후에도 유지된다:
 * <ul>
 *   <li>배치 <b>메타</b> TM = 업무 {@code DataSourceTransactionManager}(동일 인스턴스)로 BATCH_* 갱신,</li>
 *   <li>마감/도래 잡(원자적)은 step TM = 업무 {@code PlatformTransactionManager},</li>
 *   <li>계산·지급 잡(항목 단위)은 step TM = {@code ResourcelessTransactionManager}(잡 팩토리 내부)이고
 *       항목 업무는 {@code TransactionTemplate}로 1건씩 커밋 — 청크가 한도 락을 여러 항목에 걸쳐 잡지 않는다.</li>
 * </ul>
 * 잡은 이 컨텍스트에서 <b>자동 실행되지 않는다</b> — {@code BatchLaunchController}가 명시 파라미터로만 기동한다.
 */
@Configuration(proxyBeanMethods = false)
public class BatchConfig {

    @Bean
    public BatchRuntime batchRuntime(DataSource dataSource, PlatformTransactionManager metaTxManager) {
        return new BatchRuntime(dataSource, metaTxManager, "ORACLE");
    }

    @Bean
    public JobRepository jobRepository(BatchRuntime runtime) {
        return runtime.jobRepository();
    }

    @Bean
    public JobLauncher jobLauncher(BatchRuntime runtime) {
        return runtime.jobLauncher();
    }

    @Bean
    public BatchRequestRunner batchRequestRunner(JobRepository jobRepository, JobLauncher jobLauncher) {
        return new BatchRequestRunner(jobRepository, jobLauncher);
    }

    @Bean
    public Job monthCloseJob(JobRepository jobRepository, PlatformTransactionManager businessTx,
                             MonthCloseService closeService, List<CloseReport> closeReports) {
        return MonthCloseJobFactory.job(jobRepository, businessTx, closeService, closeReports);
    }

    @Bean
    public Job deferralReleaseJob(JobRepository jobRepository, PlatformTransactionManager businessTx,
                                  DeferralReleaseService releaseService) {
        return DeferralReleaseJobFactory.job(jobRepository, businessTx, releaseService);
    }

    @Bean
    public Job payoutRunJob(JobRepository jobRepository, TransactionTemplate itemTx,
                            PayoutService payoutService) {
        return PayoutRunJobFactory.job(jobRepository, itemTx, payoutService);
    }

    @Bean
    public Job recalcJob(JobRepository jobRepository, TransactionTemplate itemTx,
                         RevisionService revisionService, PolicyEventStore eventStore,
                         CommissionCalculator calculator) {
        return RecalcJobFactory.job(jobRepository, itemTx, revisionService, eventStore, calculator);
    }
}
