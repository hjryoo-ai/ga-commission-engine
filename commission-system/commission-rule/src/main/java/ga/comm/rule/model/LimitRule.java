package ga.comm.rule.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 1200%룰 버전 (LIMIT_RULE 1행). 기준일은 항상 "계약 체결일".
 *
 * @param limitMultiple     한도 배수 (예: 12.00)
 * @param fyWindowMonths    초년도 윈도우 개월수 (미결정 §11.1 — 파라미터)
 * @param overLimitAction   초과분 처리 정책 (미결정 §11.3 — 파라미터)
 * @param clawbackRestores  환수 시 한도 여유 복원 여부 (미결정 — 파라미터)
 */
public record LimitRule(
        long ruleId,
        ChannelType channelType,
        BigDecimal limitMultiple,
        int fyWindowMonths,
        OverLimitAction overLimitAction,
        boolean clawbackRestores,
        EffectivePeriod period
) {
    public LimitRule {
        Objects.requireNonNull(channelType, "channelType");
        Objects.requireNonNull(limitMultiple, "limitMultiple");
        if (overLimitAction == null) {
            // fail-fast (설계서 §6.1.4, 부록 B-13): 초과 처리 정책에 코드 기본값을 두지 않는다.
            // 정책 누락 데이터로는 한도 대상 계산 자체가 거부되어야 한다.
            throw new IllegalStateException(
                    "LIMIT_RULE.over_limit_action 누락 — 코드 기본값 없음(fail-fast). "
                            + "룰 데이터에 CUT 또는 DEFER_AFTER_FY를 등록해야 합니다 (ruleId=" + ruleId + ")");
        }
        Objects.requireNonNull(period, "period");
        if (limitMultiple.signum() <= 0) {
            throw new IllegalArgumentException("한도 배수는 양수여야 합니다: " + limitMultiple);
        }
        if (fyWindowMonths <= 0) {
            throw new IllegalArgumentException("초년도 윈도우는 1개월 이상이어야 합니다: " + fyWindowMonths);
        }
    }
}
