package ga.comm.batch;

import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Spring Batch 5 잡 저장소 조립 (설계서 §10 Phase 11).
 *
 * <p>메타테이블(BATCH_*)은 Flyway로 관리한다(Oracle: {@code db/vendor/oracle/V101}) —
 * 프레임워크 자동 초기화는 쓰지 않는다. 잡 인스턴스 = (잡 이름 × 식별 파라미터)이며
 * 이것이 §6.4 배치 계층 멱등(요청 ID dedup)의 저장소다.
 */
public final class BatchRuntime {

    private final JobRepository jobRepository;
    private final JobLauncher jobLauncher;

    public BatchRuntime(DataSource dataSource, PlatformTransactionManager metaTxManager,
                        String databaseType) {
        try {
            JobRepositoryFactoryBean factory = new JobRepositoryFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTransactionManager(metaTxManager);
            factory.setDatabaseType(databaseType);
            factory.afterPropertiesSet();
            this.jobRepository = factory.getObject();

            TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
            launcher.setJobRepository(jobRepository);
            launcher.afterPropertiesSet();
            this.jobLauncher = launcher;
        } catch (Exception e) {
            throw new IllegalStateException("Spring Batch 잡 저장소 구성 실패", e);
        }
    }

    public JobRepository jobRepository() {
        return jobRepository;
    }

    public JobLauncher jobLauncher() {
        return jobLauncher;
    }
}
