package ga.comm.infra.it;

import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.infra.OraclePersistence;
import ga.comm.rule.model.ChannelType;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.LimitRule;
import ga.comm.rule.model.OverLimitAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Oracle 전용 DDL(V100) 검증 — Phase 10 완료 기준:
 * COMM_CALC 파티셔닝, IS JSON 체크, COMM_RATE ACTIVE function-based unique index,
 * LIMIT_RULE.over_limit_action NOT NULL(데이터 계층 fail-fast), posting_seq PK.
 */
class OracleDdlFeaturesIT {

    private final OraclePersistence persistence = OracleTestSupport.persistence();

    @BeforeEach
    void clean() {
        OracleTestSupport.cleanAll();
    }

    @Test
    void COMM_CALC은_close_ym_RANGE_파티셔닝이다() throws Exception {
        // 설계서의 INTERVAL은 close_ym VARCHAR2(6)에 불가(Oracle 제약: NUMBER/DATE만) —
        // RANGE + MAXVALUE로 대체하고 연 파티션 추가는 DBA 절차로 관리한다 (V100 주석).
        try (Connection c = OracleTestSupport.dataSource().getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT partitioning_type FROM user_part_tables WHERE table_name = 'COMM_CALC'")) {
            assertThat(rs.next()).as("COMM_CALC이 파티션 테이블이어야 한다").isTrue();
            assertThat(rs.getString(1)).isEqualTo("RANGE");
        }
    }

    @Test
    void 계산_근거_컬럼은_IS_JSON_체크로_보호된다() throws Exception {
        long eventId = OracleTestSupport.newEventId();
        try (Connection c = OracleTestSupport.dataSource().getConnection();
             Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("""
                    INSERT INTO COMM_CALC (event_id, policy_no, recipient_type, recipient_id,
                        comm_type, base_amount, calc_amount, limit_cut_amt, close_ym, status,
                        rule_versions)
                    VALUES (%d, 'P-JSON', 'AGENT', 'A-1', 'FY_COMM', 1, 1, 0, '202608',
                        'CALCULATED', 'not-a-json')
                    """.formatted(eventId)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("CK_CALC_RULES_JSON");
        }
    }

    @Test
    void 같은_키_같은_개시일의_ACTIVE_요율_중복은_인덱스가_차단한다() throws Exception {
        String insert = """
                INSERT INTO COMM_RATE (direction, insurer_cd, product_key, comm_type,
                    installment_no, rate, apply_from, version_no, status)
                VALUES ('OUTBOUND', 'SAMLIFE', 'WL-20Y', 'FY_COMM', NULL, 7.0,
                    DATE '2026-01-01', 1, '%s')
                """;
        try (Connection c = OracleTestSupport.dataSource().getConnection();
             Statement s = c.createStatement()) {
            s.executeUpdate(insert.formatted("ACTIVE"));
            // 비ACTIVE는 인덱스 대상이 아니므로 얼마든지 공존한다
            s.executeUpdate(insert.formatted("DRAFT"));
            s.executeUpdate(insert.formatted("SUPERSEDED"));

            assertThatThrownBy(() -> s.executeUpdate(insert.formatted("ACTIVE")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("UX_COMM_RATE_ACTIVE");
        }
    }

    @Test
    void 같은_키_같은_개시일의_ACTIVE_시책_중복은_인덱스가_차단한다() throws Exception {
        // 요율 ux_comm_rate_active와 동형(V102). 대상 필터를 전부 NULL(전체 대상)로 두어,
        // NVL('*') 래핑이 없으면 새어나갈 "두 NULL은 서로 다르다" 경로까지 함께 차단됨을 증명한다.
        String insert = """
                INSERT INTO INCENTIVE_MST (incentive_cd, insurer_cd, product_key, channel,
                    condition_expr, payout_kind, fixed_amount, apply_from, version_no, status)
                VALUES ('PUSH-1', NULL, NULL, NULL, 'premium >= 0', 'FIXED', 100000,
                    DATE '2026-01-01', 1, '%s')
                """;
        try (Connection c = OracleTestSupport.dataSource().getConnection();
             Statement s = c.createStatement()) {
            s.executeUpdate(insert.formatted("ACTIVE"));
            // 비ACTIVE는 인덱스 대상이 아니므로 얼마든지 공존한다
            s.executeUpdate(insert.formatted("DRAFT"));
            s.executeUpdate(insert.formatted("SUPERSEDED"));

            assertThatThrownBy(() -> s.executeUpdate(insert.formatted("ACTIVE")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("UX_INCENTIVE_ACTIVE");
        }
    }

    @Test
    void over_limit_action_누락은_데이터_계층에서도_거부된다() throws Exception {
        // 모델 계층 fail-fast(부록 B-13)의 DDL 이중화 — NOT NULL
        try (Connection c = OracleTestSupport.dataSource().getConnection();
             Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("""
                    INSERT INTO LIMIT_RULE (channel_type, limit_multiple, fy_window_months,
                        over_limit_action, clawback_restores, apply_from)
                    VALUES ('GA_TO_AGENT', 12.00, 12, NULL, 'Y', DATE '2026-07-01')
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("OVER_LIMIT_ACTION");
        }
    }

    @Test
    void posting_seq는_원장_내_유일하다() throws Exception {
        long calcId = OracleTestSupport.newCalcId();
        LimitRule rule = new LimitRule(9001L, ChannelType.GA_TO_AGENT, new BigDecimal("12.00"), 12,
                OverLimitAction.DEFER_AFTER_FY, true, EffectivePeriod.from(LocalDate.of(2026, 7, 1)));
        long ledgerId = persistence.inTx(() -> persistence.limitLedgerStore()
                .getOrCreate(new PolicyNo("POL-SEQ"), new AgentId("A-1001"),
                        LocalDate.of(2026, 8, 1), Money.won(300_000), rule)
                .ledgerId());

        String insert = "INSERT INTO LIMIT_LEDGER_DTL (ledger_id, posting_seq, calc_id, amount) "
                + "VALUES (" + ledgerId + ", 1, " + calcId + ", 1000)";
        try (Connection c = OracleTestSupport.dataSource().getConnection();
             Statement s = c.createStatement()) {
            s.executeUpdate(insert);
            assertThatThrownBy(() -> s.executeUpdate(insert))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("PK_LIMIT_DTL");
        }
    }

    /** E3.1: 스냅샷 번호는 SEQUENCE(7자리 전용 대역, NOCYCLE), 일자 카운터 표는 없다. 소속 외부 키 폭은 40(자르지 않고 거부). */
    @Test
    void 스냅샷_채번은_SEQUENCE이고_소속_외부_키는_40자다() throws Exception {
        try (Connection c = OracleTestSupport.dataSource().getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT min_value, max_value, cycle_flag FROM user_sequences"
                    + " WHERE sequence_name = 'DISC_GRADE_SNAPSHOT_NO'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(1_000_000L);
                assertThat(rs.getLong(2)).isEqualTo(9_999_999L);
                assertThat(rs.getString(3)).isEqualTo("N");
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM user_tables WHERE table_name = 'DISC_GRADE_SNAPSHOT_SEQ'")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
            try (ResultSet rs = st.executeQuery("SELECT char_length FROM user_tab_columns"
                    + " WHERE table_name = 'DISC_PRODUCT_GROUP_MEMBER' AND column_name = 'EXT_PRODUCT_KEY'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(40);
            }
            st.executeUpdate("INSERT INTO DISC_PRODUCT_GROUP (group_code_system, group_code, group_name, apply_from)"
                    + " VALUES ('PG-V1', 'PG-WIDTH', 'w', DATE '2026-01-01')");
            String key40 = "ABCDEFGH:P" + "1".repeat(30);
            st.executeUpdate("INSERT INTO DISC_PRODUCT_GROUP_MEMBER (group_code_system, group_code, ext_product_key, insurer_cd,"
                    + " product_key, apply_from) VALUES ('PG-V1', 'PG-WIDTH', '" + key40 + "', 'X', 'Y', DATE '2026-01-01')");
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO DISC_PRODUCT_GROUP_MEMBER (group_code_system, group_code,"
                    + " ext_product_key, insurer_cd, product_key, apply_from) VALUES ('PG-V1', 'PG-WIDTH', '" + key40 + "2', 'X', 'Y',"
                    + " DATE '2026-01-01')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ORA-12899");
        }
    }
}
