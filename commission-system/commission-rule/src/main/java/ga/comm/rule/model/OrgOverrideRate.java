package ga.comm.rule.model;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Rate;

import java.util.Objects;

/** 조직 오버라이드 배분율 (ORG_OVERRIDE_RATE 1행). */
public record OrgOverrideRate(
        OrgLevel orgLevel,
        CommTypeCode commType,
        Rate overrideRate,
        EffectivePeriod period
) {
    public OrgOverrideRate {
        Objects.requireNonNull(orgLevel, "orgLevel");
        Objects.requireNonNull(commType, "commType");
        Objects.requireNonNull(overrideRate, "overrideRate");
        Objects.requireNonNull(period, "period");
    }
}
