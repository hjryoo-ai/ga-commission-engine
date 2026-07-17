package ga.comm.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

/**
 * 요율 승인 워크플로 매퍼 (Phase 10b, §6.6).
 * replace의 check-then-update는 rate_id 행 잠금으로 직렬화하고,
 * "겹치는 ACTIVE 없음"의 최종 심판은 ux_comm_rate_active(V100)가 맡는다.
 */
public interface CommRateAdminMapper {

    @Select("SELECT COMM_RATE_ADMIN_SEQ.NEXTVAL FROM dual")
    long nextRateId();

    @Insert("""
            INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type,
                installment_no, rate, apply_from, apply_to, version_no, status, created_by)
            VALUES (#{rateId}, #{direction}, #{insurerCd}, #{productKey}, #{commType},
                #{installmentNo}, #{rate}, #{applyFrom}, #{applyTo}, #{versionNo}, #{status}, 'workflow')
            """)
    int insert(RuleQueryMapper.CommRateRow row);

    @Select("SELECT " + RuleQueryMapper.COMM_RATE_COLUMNS
            + " FROM COMM_RATE WHERE rate_id = #{rateId} FOR UPDATE")
    RuleQueryMapper.CommRateRow lockById(long rateId);

    @Select("SELECT " + RuleQueryMapper.COMM_RATE_COLUMNS
            + " FROM COMM_RATE WHERE rate_id = #{rateId}")
    RuleQueryMapper.CommRateRow findById(long rateId);

    @Select("SELECT " + RuleQueryMapper.COMM_RATE_COLUMNS + """
              FROM COMM_RATE
             WHERE direction = #{direction} AND insurer_cd = #{insurerCd}
               AND product_key = #{productKey} AND comm_type = #{commType}
               AND NVL(installment_no, -1) = NVL(#{installmentNo}, -1)
             ORDER BY version_no
            """)
    List<RuleQueryMapper.CommRateRow> findByKey(@Param("direction") String direction,
                                                @Param("insurerCd") String insurerCd,
                                                @Param("productKey") String productKey,
                                                @Param("commType") String commType,
                                                @Param("installmentNo") Integer installmentNo);

    /** 승인 워크플로가 허용하는 변경만: apply_to(트리밍) + status(전이). */
    @Update("UPDATE COMM_RATE SET apply_to = #{applyTo}, status = #{status} WHERE rate_id = #{rateId}")
    int updatePeriodAndStatus(@Param("rateId") long rateId, @Param("applyTo") LocalDate applyTo,
                              @Param("status") String status);

    @Update("UPDATE COMM_RATE SET approved_by = #{approvedBy}, approved_at = CURRENT_TIMESTAMP "
            + "WHERE rate_id = #{rateId}")
    int recordApproval(@Param("rateId") long rateId, @Param("approvedBy") String approvedBy);

    @Insert("""
            INSERT INTO COMM_RATE_CHANGE_HIST (rate_id, change_type, old_apply_to, new_apply_to,
                old_status, new_status, changed_by)
            VALUES (#{rateId}, #{changeType}, #{oldApplyTo}, #{newApplyTo},
                #{oldStatus}, #{newStatus}, #{changedBy})
            """)
    int insertChangeHist(@Param("rateId") long rateId, @Param("changeType") String changeType,
                         @Param("oldApplyTo") LocalDate oldApplyTo,
                         @Param("newApplyTo") LocalDate newApplyTo,
                         @Param("oldStatus") String oldStatus, @Param("newStatus") String newStatus,
                         @Param("changedBy") String changedBy);

    class ChangeHistRow {
        public long rateId;
        public String changeType;
        public LocalDate oldApplyTo;
        public LocalDate newApplyTo;
        public String oldStatus;
        public String newStatus;
        public String changedBy;
    }

    @Select("""
            SELECT rate_id, change_type, old_apply_to, new_apply_to, old_status, new_status, changed_by
              FROM COMM_RATE_CHANGE_HIST
             WHERE rate_id = #{rateId}
             ORDER BY change_id
            """)
    List<ChangeHistRow> changeHistory(long rateId);
}
