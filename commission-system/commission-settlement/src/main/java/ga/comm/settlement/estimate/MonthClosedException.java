package ga.comm.settlement.estimate;

import ga.comm.domain.time.CloseYm;

/** 마감됐거나 마감 중인 달의 잠정 명세 요청(E2 ① — 그 달은 StatementConfirmed가 정본). 잡은 건너뛰고, 명세 조회(③)는 422 {@code MONTH_CLOSED}. */
public final class MonthClosedException extends RuntimeException {

    private final CloseYm ym;

    public MonthClosedException(CloseYm ym) {
        super("마감됐거나 마감 중인 달의 잠정 명세는 내지 않는다: " + ym);
        this.ym = ym;
    }

    public CloseYm ym() {
        return ym;
    }
}
