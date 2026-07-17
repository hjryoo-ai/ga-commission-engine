package ga.comm.infra.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway 마이그레이션이 H2(Oracle 호환 모드)에서 깨지지 않고 적용되는지 검증한다.
 * Oracle 실 DB 검증은 Docker 가용 환경에서 Testcontainers(oracle-free)로 수행한다.
 */
class FlywayMigrationTest {

    private static final String URL = "jdbc:h2:mem:flyway_test;MODE=Oracle;DB_CLOSE_DELAY=-1";

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(URL, "sa", "")
                .load()
                .migrate();
    }

    @Test
    void 설계서_5장의_핵심_테이블이_전부_생성된다() throws Exception {
        Set<String> tables = new HashSet<>();
        try (Connection conn = DriverManager.getConnection(URL, "sa", "");
             ResultSet rs = conn.getMetaData().getTables(null, null, "%", new String[]{"TABLE", "BASE TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME").toUpperCase());
            }
        }

        assertThat(tables).containsAll(List.of(
                "COMM_TYPE_MST", "COMM_RATE", "AGENT_PAYOUT_RATE", "LIMIT_RULE",
                "POLICY_EVENT", "COMM_CALC",
                "LIMIT_LEDGER", "LIMIT_LEDGER_DTL", "LIMIT_LEDGER_HIST",
                "DEFERRAL_CURVE", "DEFERRAL_CURVE_DTL", "DEFERRAL_SCHEDULE",
                "CLAWBACK_RULE", "CLAWBACK_RECEIVABLE", "CLAWBACK_OFFSET_HIST",
                "SETTLE_CLOSE", "ADJUSTMENT",
                "AGENT_MST", "AGENT_GRADE_HIST", "ORG_MST", "AGENT_ORG_HIST", "ORG_OVERRIDE_RATE",
                "AGENT_SETTLEMENT", "AGENT_SETTLEMENT_CALC", "INBOUND_STATEMENT",
                "COMM_RATE_CHANGE_HIST",
                "INCENTIVE_MST", "INCENTIVE_CHANGE_HIST"
        ));
    }

    @Test
    void 이벤트_멱등키는_유니크하다() throws Exception {
        try (Connection conn = DriverManager.getConnection(URL, "sa", "")) {
            conn.createStatement().executeUpdate("""
                    INSERT INTO POLICY_EVENT (event_key, policy_no, insurer_cd, product_key,
                        event_type, event_date, contract_date, agent_id, monthly_premium)
                    VALUES ('DUP-KEY-1', 'P1', 'INS1', 'PRD1', 'NEW', DATE '2026-08-01',
                        DATE '2026-08-01', 'A1', 300000)
                    """);
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    conn.createStatement().executeUpdate("""
                            INSERT INTO POLICY_EVENT (event_key, policy_no, insurer_cd, product_key,
                                event_type, event_date, contract_date, agent_id, monthly_premium)
                            VALUES ('DUP-KEY-1', 'P1', 'INS1', 'PRD1', 'NEW', DATE '2026-08-01',
                                DATE '2026-08-01', 'A1', 300000)
                            """)
            ).hasMessageContaining("UQ_POLICY_EVENT_KEY");
        }
    }
}
