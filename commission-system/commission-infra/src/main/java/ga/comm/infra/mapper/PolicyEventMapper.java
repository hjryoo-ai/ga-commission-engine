package ga.comm.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/** POLICY_EVENT 매퍼 — event_key 유니크 제약이 멱등 저장의 최종 방어선이다 (§5.4). */
public interface PolicyEventMapper {

    class Row {
        public Long eventId;
        public String eventKey;
        public String policyNo;
        public String insurerCd;
        public String productKey;
        public String eventType;
        public LocalDate eventDate;
        public LocalDate contractDate;
        public String agentId;
        public Long monthlyPremium;
        public Long paymentAmount;
        public Integer installmentNo;
        public String payload;
        public String processStatus;
    }

    @Insert("""
            INSERT INTO POLICY_EVENT (event_key, policy_no, insurer_cd, product_key, event_type,
                event_date, contract_date, agent_id, monthly_premium, payment_amount,
                installment_no, payload, process_status)
            VALUES (#{eventKey}, #{policyNo}, #{insurerCd}, #{productKey}, #{eventType},
                #{eventDate}, #{contractDate}, #{agentId}, #{monthlyPremium}, #{paymentAmount},
                #{installmentNo}, #{payload}, #{processStatus})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "eventId", keyColumn = "EVENT_ID")
    int insert(Row row);

    @Select("""
            SELECT event_id, event_key, policy_no, insurer_cd, product_key, event_type,
                   event_date, contract_date, agent_id, monthly_premium, payment_amount,
                   installment_no, payload, process_status
              FROM POLICY_EVENT
             WHERE event_key = #{eventKey}
            """)
    Row findByKey(String eventKey);

    @Select("""
            SELECT event_id, event_key, policy_no, insurer_cd, product_key, event_type,
                   event_date, contract_date, agent_id, monthly_premium, payment_amount,
                   installment_no, payload, process_status
              FROM POLICY_EVENT
             WHERE event_id = #{eventId}
            """)
    Row findById(long eventId);

    @Update("""
            UPDATE POLICY_EVENT
               SET process_status = #{status}, fail_reason = #{reason}
             WHERE event_id = #{eventId}
            """)
    int updateStatus(@Param("eventId") long eventId, @Param("status") String status,
                     @Param("reason") String reason);

    @Select("SELECT process_status FROM POLICY_EVENT WHERE event_id = #{eventId}")
    String statusOf(long eventId);

    @Select("SELECT COUNT(*) FROM POLICY_EVENT WHERE process_status IN ('PENDING', 'FAILED')")
    long unprocessedCount();
}
