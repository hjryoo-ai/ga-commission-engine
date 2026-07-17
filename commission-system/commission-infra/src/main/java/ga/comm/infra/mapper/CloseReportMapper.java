package ga.comm.infra.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/** 마감 리포트 매퍼 (설계서 §7 Phase 11) — 검출 결과를 사람이 읽는 발견 항목 문자열로 만든다. */
public interface CloseReportMapper {

    /** 룰 완결성 ①: 같은 키에 유효기간이 겹치는 ACTIVE 요율 쌍 (Ambiguous, §6.6 3차 안전망). */
    @Select("""
            SELECT 'COMM_RATE 겹치는 ACTIVE: rate ' || a.rate_id || '/' || b.rate_id
                   || ' — ' || a.direction || ' ' || a.insurer_cd || ' ' || a.product_key
                   || ' ' || a.comm_type || ' 회차 ' || NVL(TO_CHAR(a.installment_no), '-')
              FROM COMM_RATE a
              JOIN COMM_RATE b
                ON a.direction = b.direction AND a.insurer_cd = b.insurer_cd
               AND a.product_key = b.product_key AND a.comm_type = b.comm_type
               AND NVL(a.installment_no, -1) = NVL(b.installment_no, -1)
               AND a.rate_id < b.rate_id
             WHERE a.status = 'ACTIVE' AND b.status = 'ACTIVE'
               AND a.apply_from <= b.apply_to AND b.apply_from <= a.apply_to
             ORDER BY 1
            """)
    List<String> overlappingActiveRates();

    /** 룰 완결성 ②: 같은 (등급×유형)에 유효기간이 겹치는 지급률 (PK가 겹침을 막지 못한다). */
    @Select("""
            SELECT 'AGENT_PAYOUT_RATE 겹침: ' || a.grade_cd || ' ' || a.comm_type
                   || ' apply_from ' || TO_CHAR(a.apply_from, 'YYYY-MM-DD')
                   || '/' || TO_CHAR(b.apply_from, 'YYYY-MM-DD')
              FROM AGENT_PAYOUT_RATE a
              JOIN AGENT_PAYOUT_RATE b
                ON a.grade_cd = b.grade_cd AND a.comm_type = b.comm_type
               AND a.apply_from < b.apply_from
             WHERE a.apply_from <= b.apply_to AND b.apply_from <= a.apply_to
             ORDER BY 1
            """)
    List<String> overlappingPayoutRates();

    /** 룰 완결성 ③: 당월 계산에 쓰인 유형 중 기준일 유효 마스터가 없는 것 (fail-fast 사전 검출). */
    @Select("""
            SELECT DISTINCT 'COMM_TYPE_MST 누락: ' || c.comm_type
                   || ' (기준일 ' || TO_CHAR(#{baseDate}, 'YYYY-MM-DD') || ' 유효 버전 없음)'
              FROM COMM_CALC c
             WHERE c.close_ym = #{closeYm}
               AND NOT EXISTS (
                     SELECT 1 FROM COMM_TYPE_MST m
                      WHERE m.comm_type = c.comm_type
                        AND m.apply_from <= #{baseDate} AND m.apply_to >= #{baseDate})
             ORDER BY 1
            """)
    List<String> missingCommTypeMaster(@Param("closeYm") String closeYm,
                                       @Param("baseDate") LocalDate baseDate);

    /** MAXVALUE 파티션 적재 (§5.5) — 연 파티션 SPLIT 누락 안전망. */
    @Select("""
            SELECT 'pmax 적재: close_ym ' || close_ym || ' ' || COUNT(*) || '건'
              FROM COMM_CALC PARTITION (pmax)
             GROUP BY close_ym
             ORDER BY 1
            """)
    List<String> maxvaluePartitionLoad();

    /** 승인 경합 감지 (§6.6) — 같은 rate에 ACTIVATE 직후 짧은 창 안의 SUPERSEDE. */
    @Select("""
            SELECT 'rate ' || a.rate_id || ': ACTIVATE(' || a.changed_by || ') 후 '
                   || ROUND((CAST(s.changed_at AS DATE) - CAST(a.changed_at AS DATE)) * 86400)
                   || '초 내 SUPERSEDE(' || s.changed_by || ') — 근접 동시 승인 의심'
              FROM COMM_RATE_CHANGE_HIST a
              JOIN COMM_RATE_CHANGE_HIST s ON s.rate_id = a.rate_id
             WHERE a.change_type = 'ACTIVATE' AND s.change_type = 'SUPERSEDE'
               AND s.changed_at >= a.changed_at
               AND s.changed_at <= a.changed_at + NUMTODSINTERVAL(#{windowMinutes}, 'MINUTE')
             ORDER BY 1
            """)
    List<String> approvalContention(@Param("windowMinutes") int windowMinutes);
}
