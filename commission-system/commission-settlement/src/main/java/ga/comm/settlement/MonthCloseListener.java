package ga.comm.settlement;

import ga.comm.calc.store.CommCalcRecord;
import ga.comm.domain.time.CloseYm;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 마감 확정(④) 뒤 통지 포트(Phase E1 D-5) — {@link MonthCloseService#close}가 CALCULATED→CONFIRMED 전이와 CLOSED 기록을 마친 뒤
 * <b>같은 트랜잭션에서</b> 부른다. 확정 시각과 그 달의 레코드 전부(상태 무관 — 전 상태 합산 §3.0)를 넘긴다.
 * 구현은 commission-outbox(StatementConfirmed 발행)가 한다 — 업무 모듈은 outbox를 모른다.
 */
public interface MonthCloseListener {

    void onConfirmed(CloseYm closeYm, OffsetDateTime closedAt, List<CommCalcRecord> records);
}
