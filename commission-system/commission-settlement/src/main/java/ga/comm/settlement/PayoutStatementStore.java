package ga.comm.settlement;

import ga.comm.domain.time.CloseYm;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 지급명세 저장 포트(PAYOUT_STATEMENT, 설계서 §5.9·§7 ③, Phase E1 P6) — 지급 런이 확정한 세액·순액의 정본.
 * {@link PayoutService#settleOne}이 정산 행을 남긴 같은 트랜잭션에서 저장한다. 정산 행 하나에 명세 하나(insert만).
 */
public interface PayoutStatementStore {

    /** 같은 트랜잭션에서 방금 남긴 정산 행(마감월·런·수급자)에 연결해 저장한다. */
    void save(PayoutService.PayoutStatement statement, OffsetDateTime paidAt);

    List<Saved> findByCloseYm(CloseYm closeYm);

    record Saved(PayoutService.PayoutStatement statement, OffsetDateTime paidAt) {
        public Saved {
            Objects.requireNonNull(statement);
            Objects.requireNonNull(paidAt);
        }
    }
}
