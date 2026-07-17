package ga.comm.inbound;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;

import java.util.Map;
import java.util.Objects;

/**
 * 정규화된 보험사 수수료 명세 1행 (매출측 INBOUND 원장).
 * 보험사별 포맷 차이는 어댑터가 흡수하고, 이후 파이프라인은 이 형태만 다룬다.
 */
public record InboundStatement(
        InsurerCode insurerCd,
        CloseYm statementYm,
        PolicyNo policyNo,
        ProductKey productKey,
        CommTypeCode commType,
        Integer installmentNo,
        Money amount,
        Map<String, String> rawFields
) {
    public InboundStatement {
        Objects.requireNonNull(insurerCd);
        Objects.requireNonNull(statementYm);
        Objects.requireNonNull(policyNo);
        Objects.requireNonNull(productKey);
        Objects.requireNonNull(commType);
        Objects.requireNonNull(amount);
        rawFields = rawFields == null ? Map.of() : Map.copyOf(rawFields);
    }

    /** 멱등키 — 같은 명세의 중복 수신을 차단한다. */
    public String statementKey() {
        return insurerCd.value() + ":" + statementYm.value() + ":" + policyNo.value()
                + ":" + commType.value() + ":" + (installmentNo == null ? "-" : installmentNo);
    }
}
