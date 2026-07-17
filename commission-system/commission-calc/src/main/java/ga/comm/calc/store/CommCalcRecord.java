package ga.comm.calc.store;

import ga.comm.calc.CalcLine;
import ga.comm.calc.recipient.CalcTarget;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;
import ga.comm.domain.type.RecipientType;

import java.util.Objects;

/**
 * COMM_CALC 1행 — 불변 원장 레코드.
 * 생성 후 허용되는 변경은 상태 전이뿐이다. 정정은 reversal(음수) + 신규 레코드로만 한다.
 */
public record CommCalcRecord(
        Long calcId,
        long eventId,
        PolicyNo policyNo,
        RecipientType recipientType,
        String recipientId,
        CommTypeCode commType,
        Money baseAmount,
        Rate appliedRate,
        Money calcAmount,
        Money limitCutAmt,
        CloseYm closeYm,
        CalcStatus status,
        Long reversalOf,
        String ruleVersions,
        String calcTrace
) {
    public CommCalcRecord {
        Objects.requireNonNull(policyNo, "policyNo");
        Objects.requireNonNull(recipientType, "recipientType");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(commType, "commType");
        Objects.requireNonNull(baseAmount, "baseAmount");
        Objects.requireNonNull(calcAmount, "calcAmount");
        Objects.requireNonNull(limitCutAmt, "limitCutAmt");
        Objects.requireNonNull(closeYm, "closeYm");
        Objects.requireNonNull(status, "status");
    }

    /** 파이프라인 산출 라인 → CALCULATED 레코드. */
    public static CommCalcRecord calculated(PolicyEvent event, CalcTarget target, CalcLine line,
                                            CloseYm closeYm, String ruleVersions, String calcTrace) {
        return new CommCalcRecord(null, event.eventId(), event.policyNo(),
                target.recipient().type(), target.recipient().id(),
                line.commType(), line.baseAmount(), line.appliedRate(), line.amount(),
                line.limitCutAmount(), closeYm, CalcStatus.CALCULATED, null, ruleVersions, calcTrace);
    }

    public CommCalcRecord withCalcId(long id) {
        return new CommCalcRecord(id, eventId, policyNo, recipientType, recipientId, commType,
                baseAmount, appliedRate, calcAmount, limitCutAmt, closeYm, status, reversalOf,
                ruleVersions, calcTrace);
    }

    public CommCalcRecord withStatus(CalcStatus newStatus) {
        return new CommCalcRecord(calcId, eventId, policyNo, recipientType, recipientId, commType,
                baseAmount, appliedRate, calcAmount, limitCutAmt, closeYm, newStatus, reversalOf,
                ruleVersions, calcTrace);
    }
}
