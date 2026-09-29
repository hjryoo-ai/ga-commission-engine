package ga.comm.infra.it;

import ga.comm.disclosure.grade.fixture.PolicyFixtures;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;

/** 비교설명 등급 IT 시드 — 포트를 우회한 직접 SQL(시드의 순환 검증 방지). 요율은 INBOUND FY_COMM·회차 NULL. */
final class DisclosureSeed {

    static final String SYSTEM = "PG-V1";
    static final String GROUP = "PG-HEALTH-SIMPLE-NR";
    static final LocalDate FROM = LocalDate.of(2026, 1, 1);

    private final DataSource dataSource;

    DisclosureSeed(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    DisclosureSeed group() {
        exec("INSERT INTO DISC_PRODUCT_GROUP (group_code_system, group_code, group_name, apply_from) VALUES (?, ?, ?, ?)",
                SYSTEM, GROUP, "(가상) 보장성 간편 무해지", FROM);
        return this;
    }

    /** 소속 + INBOUND FY_COMM 요율. extKey = INSURER:PRODUCT. */
    DisclosureSeed product(String extKey, String rate) {
        String[] p = extKey.split(":");
        exec("INSERT INTO DISC_PRODUCT_GROUP_MEMBER (group_code_system, group_code, ext_product_key, insurer_cd, product_key, apply_from)"
                + " VALUES (?, ?, ?, ?, ?, ?)", SYSTEM, GROUP, extKey, p[0], p[1], FROM);
        exec("INSERT INTO COMM_RATE (direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status)"
                + " VALUES ('INBOUND', ?, ?, 'FY_COMM', NULL, ?, ?, 1, 'ACTIVE')", p[0], p[1], new java.math.BigDecimal(rate), FROM);
        return this;
    }

    DisclosureSeed grading(String id, LocalDate from, LocalDate to, String status, String fixture) {
        policy("DISC_GRADING_POLICY", id, from, to, status, PolicyFixtures.read(fixture));
        return this;
    }

    DisclosureSeed ranking(String id, LocalDate from, LocalDate to, String status, String fixture) {
        policy("DISC_RANKING_POLICY", id, from, to, status, PolicyFixtures.read(fixture));
        return this;
    }

    void policy(String table, String id, LocalDate from, LocalDate to, String status, String body) {
        exec("INSERT INTO " + table + " (policy_version_id, apply_from, apply_to, status, body, created_by) VALUES (?, ?, ?, ?, ?, 'it')",
                id, from, to == null ? LocalDate.of(9999, 12, 31) : to, status, body);
    }

    /** 한 행을 컬럼명(소문자) → 문자열 값으로. 없으면 빈 맵. CLOB은 문자열로 읽는다. */
    java.util.List<java.util.Map<String, String>> query(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            java.util.List<java.util.Map<String, String>> rows = new java.util.ArrayList<>();
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                java.sql.ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    java.util.Map<String, String> row = new java.util.LinkedHashMap<>();
                    for (int col = 1; col <= md.getColumnCount(); col++) {
                        row.put(md.getColumnLabel(col).toLowerCase(java.util.Locale.ROOT), rs.getString(col));
                    }
                    rows.add(row);
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    void exec(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                if (a instanceof LocalDate d) {
                    ps.setDate(i + 1, Date.valueOf(d));
                } else {
                    ps.setObject(i + 1, a);
                }
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
