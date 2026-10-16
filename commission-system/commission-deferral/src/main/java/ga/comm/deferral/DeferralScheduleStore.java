package ga.comm.deferral;

import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.time.CloseYm;

import java.util.List;

/** 분급 스케줄 저장 포트. */
public interface DeferralScheduleStore {

    List<ScheduleEntry> createAll(List<ScheduleEntry> entries);

    /** 도래분: due_ym == 당월 AND SCHEDULED. */
    List<ScheduleEntry> findDue(CloseYm dueYm);

    List<ScheduleEntry> findByPolicyAndAgent(PolicyNo policyNo, AgentId agentId);

    List<ScheduleEntry> findBySourceCalcId(long sourceCalcId);

    void replace(ScheduleEntry entry);

    /**
     * 사유를 알리는 전이(Phase E1) — 업무 코드의 전이 지점은 전부 이것을 부른다. 기본 구현은 사유를 쓰지 않는 {@link #replace(ScheduleEntry)}이고,
     * 아웃박스 데코레이터가 사유를 이벤트에 담는다(분급 모듈의 유일한 포트 변경 — E1 승인 §2).
     */
    default void replace(ScheduleEntry entry, DeferralTransitionReason reason) {
        replace(entry);
    }
}
