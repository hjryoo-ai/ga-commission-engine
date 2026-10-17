package ga.comm.infra.it;

import ga.comm.infra.OraclePersistence;
import ga.comm.outbox.OutboxEvent;
import ga.comm.outbox.OutboxStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E1 C11·D-2 — 아웃박스는 append-only다(V106 트리거). 운영자 SQL을 포함한 어떤 경로로도 행을 지우거나 내용을 고치거나 순번을 다시
 * 붙일 수 없고, 순번 표는 지울 수 없으며 되돌릴 수 없다. 허용되는 UPDATE는 미부여 행에 순번을 한 번 붙이는 것뿐이다(부여기).
 */
class OutboxAppendOnlyIT {

    private static final OraclePersistence P = OracleTestSupport.persistence();
    private static final DataSource DS = OracleTestSupport.dataSource();
    private static String assignedId;
    private static String pendingId;

    @BeforeAll
    static void rows() {
        OutboxStore store = P.outboxStore();
        OutboxEvent assigned = new OutboxEvent(UUID.randomUUID(), "AgentSnapshot", 1, "2026-09-05T18:00:00+09:00", "AGENT",
                "A-IMM", "{\"agentId\":\"A-IMM\"}");
        P.inTx(() -> store.append(assigned));
        while (P.inTx(() -> store.assignPending(1000)) > 0) {
            // 붙인다
        }
        OutboxEvent pending = new OutboxEvent(UUID.randomUUID(), "AgentSnapshot", 1, "2026-09-05T18:00:00+09:00", "AGENT",
                "A-PEND", "{\"agentId\":\"A-PEND\"}");
        P.inTx(() -> store.append(pending));
        assignedId = assigned.eventId().toString();
        pendingId = pending.eventId().toString();
    }

    private static int exec(String sql, String id) throws Exception {
        try (Connection c = DS.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (sql.contains("?")) {
                ps.setString(1, id);
            }
            return ps.executeUpdate();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM OUTBOX_EVENT WHERE event_id = ?",
            "DELETE FROM OUTBOX_EVENT WHERE event_id = ? AND 1 = 0",
            "UPDATE OUTBOX_EVENT SET payload = '{}' WHERE event_id = ?",
            "UPDATE OUTBOX_EVENT SET occurred_at = '2027-01-01T00:00:00+09:00' WHERE event_id = ?",
            "UPDATE OUTBOX_EVENT SET event_type = 'AgentSnapshot', seq = seq WHERE event_id = ?",
            "UPDATE OUTBOX_EVENT SET seq = seq + 1000000 WHERE event_id = ?",
            "UPDATE OUTBOX_EVENT SET seq = NULL WHERE event_id = ?",
    })
    void 부여된_행은_지우거나_고칠_수_없다(String sql) {
        assertThatThrownBy(() -> exec(sql, assignedId)).hasMessageMatching("(?s).*ORA-2031[123].*");
    }

    @Test
    void 미부여_행도_내용은_고칠_수_없다() {
        assertThatThrownBy(() -> exec("UPDATE OUTBOX_EVENT SET aggregate_id = 'X' WHERE event_id = ?", pendingId))
                .hasMessageContaining("ORA-20312");
        assertThatThrownBy(() -> exec("DELETE FROM OUTBOX_EVENT WHERE event_id = ?", pendingId)).hasMessageContaining("ORA-20311");
    }

    @Test
    void 순번_표는_지울_수_없고_되돌릴_수_없다() {
        assertThatThrownBy(() -> exec("DELETE FROM OUTBOX_SEQ", null)).hasMessageContaining("ORA-20314");
        assertThatThrownBy(() -> exec("UPDATE OUTBOX_SEQ SET last_seq = last_seq - 1 WHERE id = 1", null))
                .hasMessageContaining("ORA-20315");
    }

    @Test
    void payload는_JSON이어야_한다() {
        assertThatThrownBy(() -> {
            try (Connection c = DS.getConnection(); PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO OUTBOX_EVENT (event_id, event_type, schema_ver, aggregate_kind, aggregate_id, occurred_at, payload) "
                            + "VALUES (?, 'AgentSnapshot', 1, 'AGENT', 'A-X', '2026-09-05T18:00:00+09:00', 'not json')")) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.executeUpdate();
            }
        }).hasMessageContaining("CK_OUTBOX_PAYLOAD_JSON");
    }

    @Test
    void 허용되는_것은_미부여_행의_순번_부여_한_번() {
        assertThat(P.inTx(() -> P.outboxStore().assignPending(1000))).isGreaterThanOrEqualTo(1);
    }
}
