package ga.comm.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * OUTBOX_EVENT·OUTBOX_SEQ 매퍼(V105·V106, 설계서 §6.8) — 적재(insert), 순번 한 번 부여(update seq), 읽기뿐.
 * 행을 고치거나 지우는 문장은 없다(Oracle 트리거가 한 번 더 거부한다).
 */
public interface OutboxMapper {

    class Row {
        public Long eventNo;
        public String eventId;
        public Long seq;
        public String eventType;
        public int schemaVer;
        public String aggregateKind;
        public String aggregateId;
        public String occurredAt;
        public String payload;
    }

    @Insert("""
            INSERT INTO OUTBOX_EVENT (event_id, event_type, schema_ver, aggregate_kind, aggregate_id, occurred_at, payload)
            VALUES (#{eventId}, #{eventType}, #{schemaVer}, #{aggregateKind}, #{aggregateId}, #{occurredAt}, #{payload,jdbcType=CLOB})
            """)
    int insert(Row row);

    /** 부여 직렬화 — 한 행짜리 순번 표를 잠근다(D-1). 이 잠금이 없으면 두 부여기가 같은 번호를 낸다(주입 I2). */
    @Select("SELECT last_seq FROM OUTBOX_SEQ WHERE id = 1 FOR UPDATE")
    Long lockLastSeq();

    /** 보이는(=커밋된, 단독 트랜잭션에서 호출하므로 자기 미커밋 행 없음) 미부여 행을 적재 순서대로. */
    @Select("SELECT event_no FROM OUTBOX_EVENT WHERE seq IS NULL ORDER BY event_no FETCH FIRST #{max} ROWS ONLY")
    List<Long> pendingEventNos(int max);

    @Update("UPDATE OUTBOX_EVENT SET seq = #{seq} WHERE event_no = #{eventNo} AND seq IS NULL")
    int assign(@Param("eventNo") long eventNo, @Param("seq") long seq);

    @Update("UPDATE OUTBOX_SEQ SET last_seq = #{lastSeq} WHERE id = 1")
    int advance(long lastSeq);

    @Select("""
            SELECT event_no, event_id, seq, event_type, schema_ver, aggregate_kind, aggregate_id, occurred_at, payload
              FROM OUTBOX_EVENT
             WHERE seq > #{afterSeq}
             ORDER BY seq
             FETCH FIRST #{limit} ROWS ONLY
            """)
    List<Row> assignedAfter(@Param("afterSeq") long afterSeq, @Param("limit") int limit);

    @Select("SELECT last_seq FROM OUTBOX_SEQ WHERE id = 1")
    Long lastSeq();

    @Select("""
            SELECT COUNT(*) FROM OUTBOX_EVENT
             WHERE event_type = #{type} AND aggregate_kind = #{kind} AND aggregate_id = #{id}
            """)
    long count(@Param("type") String type, @Param("kind") String kind, @Param("id") String id);
}
