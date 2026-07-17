package ga.comm.calc;

import ga.comm.rule.model.EffectivePeriod;

import java.util.Objects;

/**
 * Step 활성 설정 — Step 목록 자체도 유효기간을 가진 설정이다 (설계서 §4.2).
 * 예: DeferralSplitStep은 2027-01-01부터 활성.
 */
public record StepConfig(CalculationStep step, EffectivePeriod activePeriod, int order) {
    public StepConfig {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(activePeriod, "activePeriod");
    }
}
