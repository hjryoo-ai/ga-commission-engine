package ga.comm.infra.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 룰 조회 매퍼 (Phase 10b). 모든 조회는 기준일 필수(부록 B-3) — 기준일 없는 SQL을 정의하지 않는다.
 * 유효 버전은 List로 반환하고 유일성 판정(Ambiguous)은 어댑터가 한다.
 */
public interface RuleQueryMapper {

    class CommRateRow {
        public Long rateId;
        public String direction;
        public String insurerCd;
        public String productKey;
        public String commType;
        public Integer installmentNo;
        public BigDecimal rate;
        public LocalDate applyFrom;
        public LocalDate applyTo;
        public long versionNo;
        public String status;
    }

    class CommTypeAttrRow {
        public String commType;
        public String limitIncluded;
        public String roundingPolicy;
        public String clawbackTarget;
        public LocalDate applyFrom;
        public LocalDate applyTo;
    }

    class PayoutRateRow {
        public String gradeCd;
        public String commType;
        public BigDecimal payoutRate;
        public LocalDate applyFrom;
        public LocalDate applyTo;
    }

    class LimitRuleRow {
        public long ruleId;
        public String channelType;
        public BigDecimal limitMultiple;
        public int fyWindowMonths;
        public String overLimitAction;
        public String clawbackRestores;
        public LocalDate applyFrom;
        public LocalDate applyTo;
    }

    class DeferralCurveRow {
        public long curveId;
        public String curveName;
        public LocalDate applyFrom;
        public LocalDate applyTo;
    }

    class CurvePointRow {
        public int monthNo;
        public BigDecimal pct;
    }

    class ClawbackRow {
        public long ruleId;
        public String productKey;
        public String eventType;
        public int fromInstallment;
        public int toInstallment;
        public BigDecimal clawbackPct;
        public LocalDate applyFrom;
        public LocalDate applyTo;
    }

    class OrgOverrideRow {
        public String orgLevel;
        public String commType;
        public BigDecimal overrideRate;
        public LocalDate applyFrom;
        public LocalDate applyTo;
    }

    String COMM_RATE_COLUMNS = """
            rate_id, direction, insurer_cd, product_key, comm_type, installment_no,
            rate, apply_from, apply_to, version_no, status
            """;

    @Select("SELECT " + COMM_RATE_COLUMNS + """
              FROM COMM_RATE
             WHERE status = 'ACTIVE'
               AND direction = #{direction} AND insurer_cd = #{insurerCd}
               AND product_key = #{productKey} AND comm_type = #{commType}
               AND NVL(installment_no, -1) = NVL(#{installmentNo}, -1)
               AND apply_from <= #{baseDate} AND apply_to >= #{baseDate}
            """)
    List<CommRateRow> findActiveRates(@Param("direction") String direction,
                                      @Param("insurerCd") String insurerCd,
                                      @Param("productKey") String productKey,
                                      @Param("commType") String commType,
                                      @Param("installmentNo") Integer installmentNo,
                                      @Param("baseDate") LocalDate baseDate);

    @Select("""
            SELECT comm_type, limit_included, rounding_policy, clawback_target, apply_from, apply_to
              FROM COMM_TYPE_MST
             WHERE comm_type = #{commType}
               AND apply_from <= #{baseDate} AND apply_to >= #{baseDate}
            """)
    List<CommTypeAttrRow> findCommTypeAttrs(@Param("commType") String commType,
                                            @Param("baseDate") LocalDate baseDate);

    @Select("""
            SELECT grade_cd, comm_type, payout_rate, apply_from, apply_to
              FROM AGENT_PAYOUT_RATE
             WHERE grade_cd = #{gradeCd} AND comm_type = #{commType}
               AND apply_from <= #{baseDate} AND apply_to >= #{baseDate}
            """)
    List<PayoutRateRow> findPayoutRates(@Param("gradeCd") String gradeCd,
                                        @Param("commType") String commType,
                                        @Param("baseDate") LocalDate baseDate);

    @Select("""
            SELECT rule_id, channel_type, limit_multiple, fy_window_months, over_limit_action,
                   clawback_restores, apply_from, apply_to
              FROM LIMIT_RULE
             WHERE channel_type = #{channelType}
               AND apply_from <= #{contractDate} AND apply_to >= #{contractDate}
            """)
    List<LimitRuleRow> findLimitRules(@Param("channelType") String channelType,
                                      @Param("contractDate") LocalDate contractDate);

    @Select("""
            SELECT curve_id, curve_name, apply_from, apply_to
              FROM DEFERRAL_CURVE
             WHERE apply_from <= #{contractDate} AND apply_to >= #{contractDate}
            """)
    List<DeferralCurveRow> findDeferralCurves(LocalDate contractDate);

    @Select("SELECT month_no, pct FROM DEFERRAL_CURVE_DTL WHERE curve_id = #{curveId} ORDER BY month_no")
    List<CurvePointRow> curvePoints(long curveId);

    @Select("""
            SELECT rule_id, product_key, event_type, from_installment, to_installment,
                   clawback_pct, apply_from, apply_to
              FROM CLAWBACK_RULE
             WHERE product_key = #{productKey} AND event_type = #{eventType}
               AND apply_from <= #{contractDate} AND apply_to >= #{contractDate}
             ORDER BY from_installment
            """)
    List<ClawbackRow> findClawbackRowsForProduct(@Param("productKey") String productKey,
                                                 @Param("eventType") String eventType,
                                                 @Param("contractDate") LocalDate contractDate);

    @Select("""
            SELECT rule_id, product_key, event_type, from_installment, to_installment,
                   clawback_pct, apply_from, apply_to
              FROM CLAWBACK_RULE
             WHERE product_key IS NULL AND event_type = #{eventType}
               AND apply_from <= #{contractDate} AND apply_to >= #{contractDate}
             ORDER BY from_installment
            """)
    List<ClawbackRow> findClawbackRowsCommon(@Param("eventType") String eventType,
                                             @Param("contractDate") LocalDate contractDate);

    @Select("""
            SELECT org_level, comm_type, override_rate, apply_from, apply_to
              FROM ORG_OVERRIDE_RATE
             WHERE org_level = #{orgLevel} AND comm_type = #{commType}
               AND apply_from <= #{baseDate} AND apply_to >= #{baseDate}
            """)
    List<OrgOverrideRow> findOrgOverrideRates(@Param("orgLevel") String orgLevel,
                                              @Param("commType") String commType,
                                              @Param("baseDate") LocalDate baseDate);
}
