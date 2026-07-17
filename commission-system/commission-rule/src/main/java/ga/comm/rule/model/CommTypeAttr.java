package ga.comm.rule.model;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.RoundingPolicy;

import java.util.Objects;

/**
 * 수수료 유형 속성 (COMM_TYPE_MST 1행).
 * 한도 포함 여부/반올림 정책/환수 대상 여부는 유효기간을 가진 데이터다 — 하드코딩 금지.
 */
public record CommTypeAttr(
        CommTypeCode commType,
        boolean limitIncluded,
        RoundingPolicy roundingPolicy,
        boolean clawbackTarget,
        EffectivePeriod period
) {
    public CommTypeAttr {
        Objects.requireNonNull(commType, "commType");
        Objects.requireNonNull(roundingPolicy, "roundingPolicy");
        Objects.requireNonNull(period, "period");
    }
}
