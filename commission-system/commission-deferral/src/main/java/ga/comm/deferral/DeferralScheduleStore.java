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
}
