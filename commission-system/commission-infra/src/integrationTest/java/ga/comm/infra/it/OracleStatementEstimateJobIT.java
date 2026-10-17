package ga.comm.infra.it;

import ga.comm.batch.BatchRequestRunner;
import ga.comm.batch.job.StatementEstimateJobFactory;
import ga.comm.infra.OraclePersistence;
import ga.comm.outbox.OutboxSequencer;
import ga.comm.outbox.fixture.OutboxEngine;
import ga.comm.settlement.SettleCloseStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2 ① — 잠정 명세 잡(실 Oracle): 부여 → {@code basisSeq} → 산출(읽기 전용) → 설계사마다 적재. {@code close_ym}이 없으면 오늘의 귀속월,
 * 마감된 달은 아무것도 내지 않고 {@code SKIPPED_CLOSED_MONTH}.
 */
class OracleStatementEstimateJobIT {

    private final OraclePersistence p = OracleTestSupport.persistence();
    private final JdbcTemplate jdbc = new JdbcTemplate(OracleTestSupport.dataSource());
    private OutboxEngine e;
    private Job job;

    @BeforeEach
    void setUp() {
        OracleTestSupport.cleanAll();
        e = DryRunScenario.engine(p);
        DryRunScenario.seed(p, e);
        TransactionTemplate readOnly = new TransactionTemplate(p.txManager());
        readOnly.setReadOnly(true);
        Clock clock = Clock.fixed(OffsetDateTime.parse("2026-10-31T02:10:00+09:00").toInstant(), ZoneId.of("Asia/Seoul"));
        job = StatementEstimateJobFactory.job(OracleBatchSupport.runtime().jobRepository(),
                new StatementEstimateJobFactory.Wiring(p.txTemplate(), readOnly, new OutboxSequencer(p.outboxStore(), 500), p.outboxStore(),
                        e.estimator, e.estimatePublisher, SettleCloseStore.asProvider(p.settleCloseStore()), clock));
    }

    private static JobParameters params(String closeYm) {
        JobParametersBuilder b = new JobParametersBuilder().addString("request_id", UUID.randomUUID().toString());
        if (closeYm != null) {
            b.addString("close_ym", closeYm);
        }
        return b.toJobParameters();
    }

    /** 아웃박스는 append-only라 시험 사이에 지워지지 않는다(V106 트리거) — 개수는 전후 차이로, 내용은 가장 최근 행으로 본다. */
    private long estimatedRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM OUTBOX_EVENT WHERE event_type = 'StatementEstimated'", Long.class);
    }

    private String latestEstimatePayload() {
        return jdbc.queryForObject("SELECT payload FROM OUTBOX_EVENT WHERE event_no = (SELECT MAX(event_no) FROM OUTBOX_EVENT "
                + "WHERE event_type = 'StatementEstimated')", String.class);
    }

    private static StepExecution step(BatchRequestRunner.Outcome o) {
        return o.execution().getStepExecutions().iterator().next();
    }

    @Test
    void 열린_달은_설계사마다_한_건이고_basisSeq는_부여_뒤_최대_순번이다() {
        long head = p.inTx(() -> p.outboxStore().headSeq());
        long before = estimatedRows();
        BatchRequestRunner.Outcome o = OracleBatchSupport.runner().submit(job, params("202610"));
        assertThat(o.execution().getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(step(o).getExecutionContext().getInt(StatementEstimateJobFactory.CTX_PUBLISHED)).isEqualTo(1);
        assertThat(step(o).getExecutionContext().getLong(StatementEstimateJobFactory.CTX_BASIS_SEQ)).isEqualTo(head);
        assertThat(estimatedRows()).isEqualTo(before + 1);
        String payload = latestEstimatePayload();
        assertThat(payload).contains("\"basisSeq\":" + head).contains("\"closeYm\":\"202610\"").contains("\"asOf\":\"2026-10-31T02:10:00+09:00\"");
    }

    @Test
    void close_ym이_없으면_오늘의_귀속월() {
        long before = estimatedRows();
        BatchRequestRunner.Outcome o = OracleBatchSupport.runner().submit(job, params(null));
        assertThat(o.execution().getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(estimatedRows()).isEqualTo(before + 1);
        assertThat(latestEstimatePayload()).contains("\"closeYm\":\"202610\"");
    }

    @Test
    void 마감된_달은_내지_않는다() {
        long before = estimatedRows();
        BatchRequestRunner.Outcome o = OracleBatchSupport.runner().submit(job, params("202608"));
        assertThat(step(o).getExitStatus().getExitCode()).isEqualTo(StatementEstimateJobFactory.EXIT_CLOSED);
        assertThat(estimatedRows()).isEqualTo(before);
    }
}
