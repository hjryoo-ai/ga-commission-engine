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
                "INCENTIVE_MST", "INCENTIVE_CHANGE_HIST",
                "DISC_GRADING_POLICY", "DISC_RANKING_POLICY", "DISC_PRODUCT_GROUP", "DISC_PRODUCT_GROUP_MEMBER",
                "DISC_GRADE_SNAPSHOT", "DISC_GRADE_SNAPSHOT_ITEM"
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

    /** E3.1: 스냅샷 번호 SEQUENCE는 7자리 전용 대역에서 시작하고, 소속 외부 키는 40자를 넘을 수 없다(H2 겸용 확인). */
    @Test
    void 스냅샷_번호_SEQUENCE와_소속_외부_키_폭() throws Exception {
        try (Connection conn = DriverManager.getConnection(URL, "sa", "")) {
            try (ResultSet rs = conn.createStatement().executeQuery("SELECT DISC_GRADE_SNAPSHOT_NO.NEXTVAL FROM DUAL")) {
                rs.next();
                assertThat(rs.getLong(1)).isBetween(1_000_000L, 9_999_999L);
            }
            conn.createStatement().executeUpdate("INSERT INTO DISC_PRODUCT_GROUP (group_code_system, group_code, group_name, apply_from)"
                    + " VALUES ('PG-V1', 'PG-WIDTH', 'w', DATE '2026-01-01')");
            String key40 = "ABCDEFGH:P" + "1".repeat(30);
            conn.createStatement().executeUpdate("INSERT INTO DISC_PRODUCT_GROUP_MEMBER (group_code_system, group_code, ext_product_key,"
                    + " insurer_cd, product_key, apply_from) VALUES ('PG-V1', 'PG-WIDTH', '" + key40 + "', 'X', 'Y', DATE '2026-01-01')");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> conn.createStatement().executeUpdate(
                    "INSERT INTO DISC_PRODUCT_GROUP_MEMBER (group_code_system, group_code, ext_product_key, insurer_cd, product_key,"
                            + " apply_from) VALUES ('PG-V1', 'PG-WIDTH', '" + key40 + "2', 'X', 'Y', DATE '2026-01-01')"))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }
}
