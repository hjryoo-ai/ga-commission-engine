package ga.comm.calc;

import ga.comm.domain.money.Money;

/** 해당 계약×설계사의 한도 원장 조회 뷰. 구현은 commission-limit 모듈. */
public interface LimitLedgerView {

    Money limitAmount();

    Money accumPaid();

    default Money available() {
        return limitAmount().minus(accumPaid()).max(Money.ZERO);
    }
}
