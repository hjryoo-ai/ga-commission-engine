package ga.comm.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 시책 마스터 매퍼 (Phase 13). 요율 매퍼(CommRateAdminMapper)와 동일 구조 —
 * replace의 check-then-update는 incentive_id 행 잠금으로 직렬화하고, 승인 워크플로가 허용하는
 * 변경(apply_to 트리밍 + status 전이)만 UPDATE한다.
 */
public interface IncentiveAdminMapper {

    String COLUMNS = "incentive_id, incentive_cd, insurer_cd, product_key, channel, "
            + "condition_expr, payout_kind, fixed_amount, premium_rate, "
            + "apply_from, apply_to, version_no, status";

    @Select("SELECT INCENTIVE_ADMIN_SEQ.NEXTVAL FROM dual")
    long nextIncentiveId();

    @Insert("""
            INSERT INTO INCENTIVE_MST (incentive_id, incentive_cd, insurer_cd, product_key, channel,
                condition_expr, payout_kind, fixed_amount, premium_rate,
                apply_from, apply_to, version_no, status, created_by)
            VALUES (#{incentiveId}, #{incentiveCd}, #{insurerCd}, #{productKey}, #{channel},
                #{conditionExpr}, #{payoutKind}, #{fixedAmount}, #{premiumRate},
                #{applyFrom}, #{applyTo}, #{versionNo}, #{status}, 'workflow')
            """)
    int insert(IncentiveRow row);

    @Select("SELECT " + COLUMNS + " FROM INCENTIVE_MST WHERE incentive_id = #{id} FOR UPDATE")
    IncentiveRow lockById(long id);

    @Select("SELECT " + COLUMNS + " FROM INCENTIVE_MST WHERE incentive_id = #{id}")
    IncentiveRow findById(long id);

    @Select("SELECT " + COLUMNS + " FROM INCENTIVE_MST WHERE incentive_cd = #{incentiveCd} "
            + "ORDER BY version_no")
    List<IncentiveRow> findByKey(String incentiveCd);

    @Select("SELECT " + COLUMNS + " FROM INCENTIVE_MST WHERE status = 'ACTIVE' "
            + "AND apply_from <= #{baseDate} AND apply_to >= #{baseDate}")
    List<IncentiveRow> findActiveAt(LocalDate baseDate);

    /** 승인 워크플로가 허용하는 변경만: apply_to(트리밍) + status(전이). */
    @Update("UPDATE INCENTIVE_MST SET apply_to = #{applyTo}, status = #{status} "
            + "WHERE incentive_id = #{id}")
    int updatePeriodAndStatus(@Param("id") long id, @Param("applyTo") LocalDate applyTo,
                              @Param("status") String status);

    @Update("UPDATE INCENTIVE_MST SET approved_by = #{approvedBy}, approved_at = CURRENT_TIMESTAMP "
            + "WHERE incentive_id = #{id}")
    int recordApproval(@Param("id") long id, @Param("approvedBy") String approvedBy);

    @Insert("""
            INSERT INTO INCENTIVE_CHANGE_HIST (incentive_id, change_type, old_apply_to, new_apply_to,
                old_status, new_status, changed_by)
            VALUES (#{incentiveId}, #{changeType}, #{oldApplyTo}, #{newApplyTo},
                #{oldStatus}, #{newStatus}, #{changedBy})
            """)
    int insertChangeHist(@Param("incentiveId") long incentiveId, @Param("changeType") String changeType,
                         @Param("oldApplyTo") LocalDate oldApplyTo,
                         @Param("newApplyTo") LocalDate newApplyTo,
                         @Param("oldStatus") String oldStatus, @Param("newStatus") String newStatus,
                         @Param("changedBy") String changedBy);

    @Select("""
            SELECT incentive_id, change_type, old_apply_to, new_apply_to, old_status, new_status,
                   changed_by
              FROM INCENTIVE_CHANGE_HIST
             WHERE incentive_id = #{incentiveId}
             ORDER BY change_id
            """)
    List<ChangeHistRow> changeHistory(long incentiveId);

    class IncentiveRow {
        public long incentiveId;
        public String incentiveCd;
        public String insurerCd;
        public String productKey;
        public String channel;
        public String conditionExpr;
        public String payoutKind;
        public Long fixedAmount;
        public BigDecimal premiumRate;
        public LocalDate applyFrom;
        public LocalDate applyTo;
        public long versionNo;
        public String status;
    }

    class ChangeHistRow {
        public long incentiveId;
        public String changeType;
        public LocalDate oldApplyTo;
        public LocalDate newApplyTo;
        public String oldStatus;
        public String newStatus;
        public String changedBy;
    }
}
