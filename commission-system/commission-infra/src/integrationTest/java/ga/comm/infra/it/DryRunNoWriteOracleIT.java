package ga.comm.infra.it;

import ga.comm.infra.OraclePersistence;
import ga.comm.outbox.fixture.OutboxEngine;
import ga.comm.settlement.estimate.StatementEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2 C1 — 잠정 명세(①)는 업무 표에 아무것도 쓰지 않는다. 실제 Oracle에서 업무 표 12개의 전 행 해시가 전후 같고, 아웃박스는 기존 행이 전부
 * 바이트로 같으며(부여는 시작 전에 끝냈다 — P13) 새 행은 {@code StatementEstimated}뿐이다. 표별 해시는 보고서용으로
 * {@code build/reports/dry-run/c1-hashes.md}에 쓴다.
 */
class DryRunNoWriteOracleIT {

    private final OraclePersistence p = OracleTestSupport.persistence();
    private final JdbcTemplate jdbc = new JdbcTemplate(OracleTestSupport.dataSource());
    private OutboxEngine e;

    @BeforeEach
    void setUp() {
        OracleTestSupport.cleanAll();
        e = DryRunScenario.engine(p);
        DryRunScenario.seed(p, e);
    }

    @Test
    void 잠정명세_전후_업무표가_같다() throws Exception {
        Map<String, String> before = DryRunScenario.hashes(jdbc);
        List<String> outboxBefore = DryRunScenario.rows(jdbc, "SELECT * FROM OUTBOX_EVENT");
        long rowsBefore = outboxBefore.size();

        List<StatementEstimator.Estimate> estimates = p.inTx(() -> e.estimateAndPublish(DryRunScenario.OPEN_MONTH));

        Map<String, String> after = DryRunScenario.hashes(jdbc);
        assertThat(after).as("업무 표 12개 전후 해시").isEqualTo(before);
        assertThat(estimates).isNotEmpty();
        assertThat(estimates.getFirst().math().receivableOffset().isPositive()).as("채권 상계 미리보기가 있는 시나리오(공허하지 않다)").isTrue();
        List<String> outboxAfter = DryRunScenario.rows(jdbc, "SELECT * FROM OUTBOX_EVENT");
        assertThat(outboxAfter).as("기존 아웃박스 행은 바이트로 그대로").containsAll(outboxBefore);
        List<String> added = outboxAfter.stream().filter(r -> !outboxBefore.contains(r)).toList();
        assertThat(added).hasSize(estimates.size()).allMatch(r -> r.contains("EVENT_TYPE=StatementEstimated|"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM OUTBOX_EVENT", Long.class)).isEqualTo(rowsBefore + estimates.size());

        StringBuilder md = new StringBuilder("| 표 | 전 | 후 |\n|---|---|---|\n");
        before.forEach((t, h) -> md.append("| ").append(t).append(" | ").append(h).append(" | ").append(after.get(t)).append(" |\n"));
        md.append("| OUTBOX_EVENT | ").append(rowsBefore).append("행 | ").append(outboxAfter.size()).append("행(+").append(added.size())
                .append(" StatementEstimated) |\n");
        Path out = Path.of(System.getProperty("user.dir"), "build", "reports", "dry-run", "c1-estimate.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md, StandardCharsets.UTF_8);
    }
}
