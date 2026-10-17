package ga.comm.settlement;

import ga.comm.calc.store.CommCalcRecord;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 수급자 1명 정산 뒤 통지 포트(Phase E1 D-6) — {@link PayoutService#settleOne}이 정산 행·PAID 전이·지급명세 저장을 마친 뒤
 * <b>같은 항목 트랜잭션에서</b> 부른다. 그 정산에 편입된 레코드(상태 무관)와 지급 시각을 넘긴다. 구현은 commission-outbox(PayoutCompleted).
 */
public interface PayoutListener {

    void onSettled(PayoutService.PayoutStatement statement, SettlementRow settlement, List<CommCalcRecord> records,
                   OffsetDateTime paidAt);
}
