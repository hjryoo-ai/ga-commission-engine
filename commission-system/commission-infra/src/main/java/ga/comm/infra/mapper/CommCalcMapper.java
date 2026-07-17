package ga.comm.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/**
 * COMM_CALC 매퍼 — 불변 원장 (§5.5, 부록 B-2).
 * INSERT와 status 갱신 SQL만 존재한다. 금액·귀속월을 바꾸는 UPDATE는 정의하지 않는다.
 */
public interface CommCalcMapper {

    String COLUMNS = """
            calc_id, event_id, policy_no, recipient_type, recipient_id, comm_type,
            base_amount, applied_rate, calc_amount, limit_cut_amt, close_ym, status,
            reversal_of, rule_versions, calc_trace
            """;

    class Row {
        public Long calcId;
        public long eventId;
        public String policyNo;
        public String recipientType;
        public String recipientId;
        public String commType;
        public long baseAmount;
        public BigDecimal appliedRate;
        public long calcAmount;
        public long limitCutAmt;
        public String closeYm;
        public String status;
        public Long reversalOf;
        public String ruleVersions;
        public String calcTrace;
    }

    @Insert("""
            INSERT INTO COMM_CALC (event_id, policy_no, recipient_type, recipient_id, comm_type,
                base_amount, applied_rate, calc_amount, limit_cut_amt, close_ym, status,
                reversal_of, rule_versions, calc_trace)
            VALUES (#{eventId}, #{policyNo}, #{recipientType}, #{recipientId}, #{commType},
                #{baseAmount}, #{appliedRate}, #{calcAmount}, #{limitCutAmt}, #{closeYm}, #{status},
                #{reversalOf}, #{ruleVersions}, #{calcTrace})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "calcId", keyColumn = "CALC_ID")
    int insert(Row row);

    @Select("SELECT " + COLUMNS + " FROM COMM_CALC WHERE calc_id = #{calcId}")
    Row findById(long calcId);

    @Select("SELECT " + COLUMNS + " FROM COMM_CALC WHERE event_id = #{eventId} ORDER BY calc_id")
    List<Row> findByEventId(long eventId);

    @Select("SELECT " + COLUMNS + " FROM COMM_CALC "
            + "WHERE policy_no = #{policyNo} AND recipient_id = #{recipientId} ORDER BY calc_id")
    List<Row> findByPolicyAndRecipient(@Param("policyNo") String policyNo,
                                       @Param("recipientId") String recipientId);

    @Select("SELECT " + COLUMNS + " FROM COMM_CALC WHERE close_ym = #{closeYm} ORDER BY calc_id")
    List<Row> findByCloseYm(String closeYm);

    /** 상태 전이 검증을 위한 행 잠금 — 전이의 check-then-update를 직렬화한다. */
    @Select("SELECT status FROM COMM_CALC WHERE calc_id = #{calcId} FOR UPDATE")
    String lockStatus(long calcId);

    @Update("UPDATE COMM_CALC SET status = #{status} WHERE calc_id = #{calcId}")
    int updateStatus(@Param("calcId") long calcId, @Param("status") String status);
}
