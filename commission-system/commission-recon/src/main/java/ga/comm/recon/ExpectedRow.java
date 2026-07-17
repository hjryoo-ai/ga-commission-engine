package ga.comm.recon;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;

import java.util.Objects;

/** 자체 계산 기대치 1행 — 보험사가 이 금액을 지급했어야 한다 (INBOUND 방향 요율 기준). */
public record ExpectedRow(
        PolicyNo policyNo,
        CommTypeCode commType,
        Integer installmentNo,
        Money amount
) {
    public ExpectedRow {
        Objects.requireNonNull(policyNo);
        Objects.requireNonNull(commType);
        Objects.requireNonNull(amount);
    }

    public String matchKey() {
        return policyNo.value() + ":" + commType.value() + ":"
                + (installmentNo == null ? "-" : installmentNo);
    }
}
