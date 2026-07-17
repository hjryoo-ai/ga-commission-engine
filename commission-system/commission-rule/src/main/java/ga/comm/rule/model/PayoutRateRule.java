package ga.comm.rule.model;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Rate;

import java.util.Objects;

/** 설계사 등급별 지급률 (AGENT_PAYOUT_RATE 1행). */
public record PayoutRateRule(
        String gradeCd,
        CommTypeCode commType,
        Rate payoutRate,
        EffectivePeriod period
) {
    public PayoutRateRule {
        Objects.requireNonNull(gradeCd, "gradeCd");
        Objects.requireNonNull(commType, "commType");
        Objects.requireNonNull(payoutRate, "payoutRate");
        Objects.requireNonNull(period, "period");
    }
}
