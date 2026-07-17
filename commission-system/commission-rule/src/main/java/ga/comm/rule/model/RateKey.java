package ga.comm.rule.model;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.type.Direction;

/** 요율의 업무 키. 같은 키 안에서 유효기간이 서로 겹치는 ACTIVE 레코드는 존재할 수 없다. */
public record RateKey(
        Direction direction,
        InsurerCode insurerCd,
        ProductKey productKey,
        CommTypeCode commType,
        Integer installmentNo
) {
}
