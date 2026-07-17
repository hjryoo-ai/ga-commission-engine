package ga.comm.shadow;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.RecipientType;

/**
 * 외부 정산 결과 한 행 (섀도 런의 대조 기준, 설계서 §8.5). 기존 시스템 또는 수기 엑셀에서 넘어온
 * 정산 값을 <b>수급자×유형×마감월</b> 축으로 담는다 — 자체 계산 순액과 이 축으로 대조한다.
 */
public record ExternalSettlementRow(RecipientType recipientType, String recipientId,
                                    CommTypeCode commType, CloseYm closeYm, Money amount) {

    /** 대조 매칭키: 수급유형:수급자:유형:마감월. */
    public String matchKey() {
        return recipientType + ":" + recipientId + ":" + commType.value() + ":" + closeYm.value();
    }
}
