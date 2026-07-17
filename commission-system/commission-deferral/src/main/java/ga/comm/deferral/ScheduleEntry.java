package ga.comm.deferral;

import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;

import java.util.Objects;

/** DEFERRAL_SCHEDULE 1행. */
public record ScheduleEntry(
        Long scheduleId,
        long sourceCalcId,
        PolicyNo policyNo,
        AgentId agentId,
        CloseYm dueYm,
        Money amount,
        String payCondition,
        ScheduleStatus status,
        long curveVersionId,
        Long releasedCalcId
) {
    public static final String CONDITION_POLICY_INFORCE = "POLICY_INFORCE";

    public ScheduleEntry {
        Objects.requireNonNull(policyNo);
        Objects.requireNonNull(agentId);
        Objects.requireNonNull(dueYm);
        Objects.requireNonNull(amount);
        Objects.requireNonNull(payCondition);
        Objects.requireNonNull(status);
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("분급 금액은 양수여야 합니다: " + amount);
        }
    }

    public ScheduleEntry withId(long id) {
        return new ScheduleEntry(id, sourceCalcId, policyNo, agentId, dueYm, amount, payCondition,
                status, curveVersionId, releasedCalcId);
    }

    public ScheduleEntry released(long calcId) {
        requireStatus(ScheduleStatus.SCHEDULED);
        return new ScheduleEntry(scheduleId, sourceCalcId, policyNo, agentId, dueYm, amount,
                payCondition, ScheduleStatus.RELEASED, curveVersionId, calcId);
    }

    public ScheduleEntry held() {
        requireStatus(ScheduleStatus.SCHEDULED);
        return new ScheduleEntry(scheduleId, sourceCalcId, policyNo, agentId, dueYm, amount,
                payCondition, ScheduleStatus.HELD, curveVersionId, null);
    }

    public ScheduleEntry cancelled() {
        if (status == ScheduleStatus.RELEASED) {
            throw new IllegalStateException("이미 지급 투입된 스케줄은 취소할 수 없습니다: " + scheduleId);
        }
        return new ScheduleEntry(scheduleId, sourceCalcId, policyNo, agentId, dueYm, amount,
                payCondition, ScheduleStatus.CANCELLED, curveVersionId, null);
    }

    private void requireStatus(ScheduleStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "허용되지 않는 스케줄 전이: " + status + " (기대: " + expected + ", id=" + scheduleId + ")");
        }
    }
}
