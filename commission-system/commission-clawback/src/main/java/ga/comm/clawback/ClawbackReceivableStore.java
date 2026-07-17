package ga.comm.clawback;

import ga.comm.domain.id.AgentId;
import ga.comm.domain.money.Money;

import java.util.List;

/** 환수 채권 저장 포트 (CLAWBACK_RECEIVABLE + OFFSET_HIST). */
public interface ClawbackReceivableStore {

    ClawbackReceivable create(AgentId agentId, Long originCalcId, Money amount);

    /** 상계 가능한(OPEN/OFFSET) 채권 — 오래된 것부터. */
    List<ClawbackReceivable> findOffsettable(AgentId agentId);

    void save(ClawbackReceivable receivable);

    /** 상계 이력 (어느 지급 calc에서 얼마를 상계했는지). */
    void recordOffset(long receivableId, long calcId, Money amount);
}
