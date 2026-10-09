package ga.comm.infra.it;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E3.2(V104), 실 Oracle: 스냅샷 항목 상품 키 폭 40 — 40자 통과·41자 ORA-12899. 마이그레이션은 값을 바꾸지 않는다: V103까지 적용한 별도 스키마에
 * 40자 이하 행을 두면 V104 뒤 그대로이고, 41자 행이 하나라도 있으면 V104가 ORA-01441로 실패한다(자르지 않는다). 불변 트리거(V103)는 DML
 * 트리거라 이 DDL을 막지 않는다. 항목 행은 지울 수 없으므로(트리거) 공유 스키마의 경계 시험은 트랜잭션을 되돌린다.
 */
class SnapshotItemKeyWidthIT {

    static final String KEY40 = "ABCDEFGH:P" + "1".repeat(30);
    static final String KEY41 = KEY40 + "2";

    static void migrate(DataSource ds, String target) {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration", "classpath:db/vendor/oracle").target(target).load().migrate();
    }

    static void snapshot(Statement st, String id) throws SQLException {
        st.executeUpdate("INSERT INTO DISC_GRADE_SNAPSHOT (snapshot_id, tenant_id, as_of_date, product_group_code, grading_policy_version_id,"
                + " ranking_policy_version_id, tie_break, basis_json, generated_at, response_canonical, response_sha256) VALUES ('" + id
                + "', 'T1', DATE '2026-09-23', 'PG', 'G1', 'R1', 'SHARED_RANK', '{}', TIMESTAMP '2026-09-23 10:15:30 +09:00', '{}', '"
                + "0".repeat(64) + "')");
    }

    static void item(Statement st, String id, String key, int order) throws SQLException {
        st.executeUpdate("INSERT INTO DISC_GRADE_SNAPSHOT_ITEM (snapshot_id, product_key, item_order, status, reason) VALUES ('" + id
                + "', '" + key + "', " + order + ", 'UNAVAILABLE', 'NO_RATE_DATA')");
    }

    static List<String> keys(Statement st, String id) throws SQLException {
        List<String> keys = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT product_key FROM DISC_GRADE_SNAPSHOT_ITEM WHERE snapshot_id = '" + id + "' ORDER BY item_order")) {
            while (rs.next()) {
                keys.add(rs.getString(1));
            }
        }
        return keys;
    }

    static int width(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT char_length FROM user_tab_columns"
                + " WHERE table_name = 'DISC_GRADE_SNAPSHOT_ITEM' AND column_name = 'PRODUCT_KEY'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void 길이_40은_저장되고_41은_ORA_12899() throws Exception {
        try (Connection c = OracleTestSupport.dataSource().getConnection(); Statement st = c.createStatement()) {
            assertThat(width(st)).isEqualTo(40);
            c.setAutoCommit(false);
            try {
                snapshot(st, "GRD-19990101-0000001");                         // 채번 대역(V13, 1000000~) 밖 — 되돌린다
                item(st, "GRD-19990101-0000001", KEY40, 1);
                assertThat(keys(st, "GRD-19990101-0000001")).containsExactly(KEY40);
                assertThatThrownBy(() -> item(st, "GRD-19990101-0000001", KEY41, 2))
                        .isInstanceOf(SQLException.class).hasMessageContaining("ORA-12899");
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }
        }
    }

    @Test
    void V104는_40자_이하_행을_바꾸지_않고_41자_행이_있으면_ORA_01441로_실패한다() throws Exception {
        try (HikariDataSource fit = OracleTestSupport.freshSchema("E32_V104_FIT")) {
            migrate(fit, "103");
            try (Connection c = fit.getConnection(); Statement st = c.createStatement()) {
                assertThat(width(st)).isEqualTo(129);
                snapshot(st, "GRD-20260923-0000002");
                item(st, "GRD-20260923-0000002", KEY40, 1);
                item(st, "GRD-20260923-0000002", "A:1", 2);
                item(st, "GRD-20260923-0000002", "INS-A:p.r_d-1", 3);
            }
            migrate(fit, "latest");
            try (Connection c = fit.getConnection(); Statement st = c.createStatement()) {
                assertThat(width(st)).isEqualTo(40);
                assertThat(keys(st, "GRD-20260923-0000002")).containsExactly(KEY40, "A:1", "INS-A:p.r_d-1");
            }
        }
        try (HikariDataSource wide = OracleTestSupport.freshSchema("E32_V104_WIDE")) {
            migrate(wide, "103");
            try (Connection c = wide.getConnection(); Statement st = c.createStatement()) {
                snapshot(st, "GRD-20260923-0000003");
                item(st, "GRD-20260923-0000003", KEY41, 1);
            }
            assertThatThrownBy(() -> migrate(wide, "latest")).isInstanceOf(FlywayException.class).hasMessageContaining("ORA-01441");
            try (Connection c = wide.getConnection(); Statement st = c.createStatement()) {
                assertThat(width(st)).isEqualTo(129);
                assertThat(keys(st, "GRD-20260923-0000003")).containsExactly(KEY41);
            }
        }
    }
}
