package ga.comm.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 비교설명 등급·순위 매퍼(Phase E3). 정책·소속 조회는 기준일 필수(부록 B-3) — 유효 버전은 List로 반환하고
 * 유일성(Ambiguous)은 해석기가 판정한다. 스냅샷은 INSERT·SELECT만 있다(UPDATE·DELETE 문장이 없고 DB 트리거가 거부한다).
 */
public interface DisclosureGradeMapper {

    class PolicyRowData {
        public String policyVersionId;
        public LocalDate applyFrom;
        public LocalDate applyTo;
        public String status;
        public String body;
    }

    class MemberRow {
        public String extProductKey;
        public String insurerCd;
        public String productKey;
    }

    class SnapshotRow {
        public String snapshotId;
        public String tenantId;
        public String responseCanonical;
        public String responseSha256;
    }

    class SnapshotItemRow {
        public String productKey;
        public int itemOrder;
        public String status;
        public String ratioToAvg;
        public String grade;
        public String gradeLabel;
        public Integer gradeOrdinal;
        public Integer rankInSet;
        public Integer tie;
        public String reason;
    }

    @Select("""
            SELECT policy_version_id, apply_from, apply_to, status, body
              FROM DISC_GRADING_POLICY
             WHERE status = 'ACTIVE' AND apply_from <= #{asOf} AND apply_to >= #{asOf}
             ORDER BY policy_version_id
            """)
    List<PolicyRowData> activeGradingPolicies(@Param("asOf") LocalDate asOf);

    @Select("""
            SELECT policy_version_id, apply_from, apply_to, status, body
              FROM DISC_RANKING_POLICY
             WHERE status = 'ACTIVE' AND apply_from <= #{asOf} AND apply_to >= #{asOf}
             ORDER BY policy_version_id
            """)
    List<PolicyRowData> activeRankingPolicies(@Param("asOf") LocalDate asOf);

    @Select("""
            SELECT COUNT(*) FROM DISC_PRODUCT_GROUP
             WHERE group_code_system = #{system} AND group_code = #{code}
               AND apply_from <= #{date} AND apply_to >= #{date}
            """)
    int countGroups(@Param("system") String system, @Param("code") String code, @Param("date") LocalDate date);

    @Select("""
            SELECT ext_product_key, insurer_cd, product_key
              FROM DISC_PRODUCT_GROUP_MEMBER
             WHERE group_code_system = #{system} AND group_code = #{code}
               AND apply_from <= #{date} AND apply_to >= #{date}
             ORDER BY ext_product_key
            """)
    List<MemberRow> members(@Param("system") String system, @Param("code") String code, @Param("date") LocalDate date);

    /** 기간 무관 ACTIVE INBOUND 요율 존재 여부(OUTSIDE_PERIOD vs NO_RATE_DATA). */
    @Select("""
            SELECT COUNT(*) FROM COMM_RATE
             WHERE status = 'ACTIVE' AND direction = 'INBOUND'
               AND insurer_cd = #{insurerCd} AND product_key = #{productKey} AND comm_type = #{commType}
               AND NVL(installment_no, -1) = NVL(#{installmentNo}, -1)
            """)
    int countInboundRates(@Param("insurerCd") String insurerCd, @Param("productKey") String productKey,
                          @Param("commType") String commType, @Param("installmentNo") Integer installmentNo);

    @Select("SELECT last_no FROM DISC_GRADE_SNAPSHOT_SEQ WHERE seq_date = #{day} FOR UPDATE")
    Integer lockSequence(@Param("day") LocalDate day);

    @Insert("INSERT INTO DISC_GRADE_SNAPSHOT_SEQ (seq_date, last_no) VALUES (#{day}, 1)")
    int insertSequence(@Param("day") LocalDate day);

    @Update("UPDATE DISC_GRADE_SNAPSHOT_SEQ SET last_no = last_no + 1 WHERE seq_date = #{day}")
    int incrementSequence(@Param("day") LocalDate day);

    @Insert("""
            INSERT INTO DISC_GRADE_SNAPSHOT (snapshot_id, tenant_id, as_of_date, product_group_code,
                grading_policy_version_id, ranking_policy_version_id, tie_break, basis_json, generated_at,
                response_canonical, response_sha256)
            VALUES (#{snapshotId}, #{tenantId}, #{asOfDate}, #{productGroupCode}, #{gradingPolicyVersionId},
                #{rankingPolicyVersionId}, #{tieBreak}, #{basisJson}, #{generatedAt}, #{responseCanonical}, #{responseSha256})
            """)
    int insertSnapshot(@Param("snapshotId") String snapshotId, @Param("tenantId") String tenantId,
                       @Param("asOfDate") LocalDate asOfDate, @Param("productGroupCode") String productGroupCode,
                       @Param("gradingPolicyVersionId") String gradingPolicyVersionId,
                       @Param("rankingPolicyVersionId") String rankingPolicyVersionId, @Param("tieBreak") String tieBreak,
                       @Param("basisJson") String basisJson, @Param("generatedAt") OffsetDateTime generatedAt,
                       @Param("responseCanonical") String responseCanonical, @Param("responseSha256") String responseSha256);

    @Insert("""
            INSERT INTO DISC_GRADE_SNAPSHOT_ITEM (snapshot_id, product_key, item_order, status, ratio_to_avg, grade,
                grade_label, grade_ordinal, rank_in_set, tie, reason)
            VALUES (#{snapshotId}, #{item.productKey}, #{item.itemOrder}, #{item.status}, #{item.ratioToAvg}, #{item.grade},
                #{item.gradeLabel}, #{item.gradeOrdinal}, #{item.rankInSet}, #{item.tie}, #{item.reason})
            """)
    int insertItem(@Param("snapshotId") String snapshotId, @Param("item") SnapshotItemRow item);

    @Select("""
            SELECT snapshot_id, tenant_id, response_canonical, response_sha256
              FROM DISC_GRADE_SNAPSHOT WHERE snapshot_id = #{snapshotId}
            """)
    SnapshotRow findSnapshot(@Param("snapshotId") String snapshotId);

    @Select("""
            SELECT product_key, item_order, status, ratio_to_avg, grade, grade_label, grade_ordinal, rank_in_set, tie, reason
              FROM DISC_GRADE_SNAPSHOT_ITEM WHERE snapshot_id = #{snapshotId} ORDER BY item_order
            """)
    List<SnapshotItemRow> findItems(@Param("snapshotId") String snapshotId);
}
