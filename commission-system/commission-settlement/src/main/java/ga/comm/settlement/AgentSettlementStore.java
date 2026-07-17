package ga.comm.settlement;

import ga.comm.domain.time.CloseYm;

import java.util.List;

/** 마감 정산 내역 저장 포트 — 지급 배치의 입력이 된다. */
public interface AgentSettlementStore {

    void saveAll(List<SettlementRow> rows);

    List<SettlementRow> findByCloseYm(CloseYm closeYm);
}
