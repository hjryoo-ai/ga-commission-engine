package ga.comm.infra.it;

import ga.comm.infra.OraclePersistence;
import ga.comm.outbox.fixture.OutboxEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2 C3 — 다른 커넥션이 원장·계산·채권·분급·마감 행을 {@code FOR UPDATE}로 쥔 채로 잠정 명세(①)를 돌려도 기다리지 않는다(읽기만, 잠금 없는
 * SELECT). 5초 안에 끝나지 않으면 실패한다(잠금 대기면 무한정 기다린다).
 */
class DryRunLockOracleIT {

    private final OraclePersistence p = OracleTestSupport.persistence();
    private OutboxEngine e;
    private Connection holder;

    @BeforeEach
    void setUp() throws Exception {
        OracleTestSupport.cleanAll();
        e = DryRunScenario.engine(p);
        DryRunScenario.seed(p, e);
        holder = OracleTestSupport.dataSource().getConnection();
        holder.setAutoCommit(false);
        try (Statement s = holder.createStatement()) {
            for (String t : List.of("LIMIT_LEDGER", "COMM_CALC", "CLAWBACK_RECEIVABLE", "DEFERRAL_SCHEDULE", "SETTLE_CLOSE")) {
                s.execute("SELECT * FROM " + t + " FOR UPDATE");
            }
        }
    }

    @AfterEach
    void release() throws Exception {
        holder.rollback();
        holder.close();
    }

    @Test
    void 원장_잠금_중에도_잠정명세는_기다리지_않는다() throws Exception {
        var estimates = CompletableFuture.supplyAsync(() -> p.inTx(() -> e.estimateAndPublish(DryRunScenario.OPEN_MONTH)))
                .get(5, TimeUnit.SECONDS);
        assertThat(estimates).isNotEmpty();
    }
}
