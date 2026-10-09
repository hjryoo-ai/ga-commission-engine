package ga.comm.infra.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E3.2(V104): 스냅샷 항목 상품 키 폭 129 → 40 — H2(MODE=Oracle) 빠른 티어. 계약 ProductKey의 최대 길이 40(보험사 8 + ':' + 상품 코드 31)은
 * 통과하고 41은 거부한다. 마이그레이션은 값을 바꾸지 않는다: V104 직전 스키마에 40자 이하 행이 있으면 그대로 남고, 41자 행이 하나라도 있으면
 * V104가 실패한다(자르지 않는다). Oracle 실 DB는 {@code SnapshotItemKeyWidthIT}.
 */
class SnapshotItemKeyWidthTest {

    static final String KEY40 = "ABCDEFGH:P" + "1".repeat(30);
    static final String KEY41 = KEY40 + "2";

    private static String url(String name) {
        return "jdbc:h2:mem:" + name + ";MODE=Oracle;DB_CLOSE_DELAY=-1";
    }

    private static void migrate(String url, String target) {
        Flyway.configure().dataSource(url, "sa", "").target(target).load().migrate();
    }

    static void snapshot(Statement st, String id) throws SQLException {
        st.executeUpdate("INSERT INTO DISC_GRADE_SNAPSHOT (snapshot_id, tenant_id, as_of_date, product_group_code, grading_policy_version_id,"
                + " ranking_policy_version_id, tie_break, basis_json, generated_at, response_canonical, response_sha256) VALUES ('" + id
                + "', 'T1', DATE '2026-09-23', 'PG', 'G1', 'R1', 'SHARED_RANK', '{}', TIMESTAMP '2026-09-23 10:15:30+09:00', '{}', '"
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
        try (ResultSet rs = st.executeQuery("SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS"
                + " WHERE TABLE_NAME = 'DISC_GRADE_SNAPSHOT_ITEM' AND COLUMN_NAME = 'PRODUCT_KEY'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void 길이_40은_저장되고_41은_거부된다() throws Exception {
        String url = url("v104_boundary");
        migrate(url, "latest");
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement st = c.createStatement()) {
            assertThat(width(st)).isEqualTo(40);
            snapshot(st, "GRD-20260923-0000001");
            item(st, "GRD-20260923-0000001", KEY40, 1);
            assertThatThrownBy(() -> item(st, "GRD-20260923-0000001", KEY41, 2)).isInstanceOf(SQLException.class);
            assertThat(keys(st, "GRD-20260923-0000001")).containsExactly(KEY40);
        }
    }

    @Test
    void V104는_40자_이하_행을_바꾸지_않는다() throws Exception {
        String url = url("v104_fit");
        migrate(url, "14");                                                   // H2: 공통 디렉터리만 — V14 다음이 V104
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement st = c.createStatement()) {
            assertThat(width(st)).isEqualTo(129);
            snapshot(st, "GRD-20260923-0000002");
            item(st, "GRD-20260923-0000002", KEY40, 1);
            item(st, "GRD-20260923-0000002", "A:1", 2);
            item(st, "GRD-20260923-0000002", "INS-A:p.r_d-1", 3);
        }
        migrate(url, "latest");
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement st = c.createStatement()) {
            assertThat(width(st)).isEqualTo(40);
            assertThat(keys(st, "GRD-20260923-0000002")).containsExactly(KEY40, "A:1", "INS-A:p.r_d-1");
        }
    }

    @Test
    void 사십일자_행이_있으면_V104가_실패한다_자르지_않는다() throws Exception {
        String url = url("v104_wide");
        migrate(url, "14");
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement st = c.createStatement()) {
            snapshot(st, "GRD-20260923-0000003");
            item(st, "GRD-20260923-0000003", KEY41, 1);
        }
        assertThatThrownBy(() -> migrate(url, "latest")).isInstanceOf(FlywayException.class);
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement st = c.createStatement()) {
            assertThat(keys(st, "GRD-20260923-0000003")).containsExactly(KEY41);
        }
    }
}
