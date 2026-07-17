package ga.comm.calc;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.domain.money.RoundingPolicy;

import java.util.Objects;

/**
 * 계산 라인 — COMM_CALC 1행에 대응한다. Step들이 추가/치환하며, 라인 자체는 불변이다.
 *
 * @param commType       수수료 유형
 * @param baseAmount     산출 기준액
 * @param appliedRate    적용 요율 (정액 지급이면 null)
 * @param amount         지급 예정액 (환수는 음수)
 * @param limitCutAmount 1200% 한도로 삭감/이연된 금액
 * @param limitIncluded  기준일 유효 COMM_TYPE 속성의 한도 포함 여부 스냅샷
 * @param roundingPolicy 이 라인에 적용된 반올림 정책
 * @param sourceRateId   사용한 요율 룰 ID (근거 박제, 없으면 null)
 * @param memo           산출 근거 메모
 */
public record CalcLine(
        CommTypeCode commType,
        Money baseAmount,
        Rate appliedRate,
        Money amount,
        Money limitCutAmount,
        boolean limitIncluded,
        RoundingPolicy roundingPolicy,
        Long sourceRateId,
        String memo
) {
    public CalcLine {
        Objects.requireNonNull(commType, "commType");
        Objects.requireNonNull(baseAmount, "baseAmount");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(limitCutAmount, "limitCutAmount");
        Objects.requireNonNull(roundingPolicy, "roundingPolicy");
    }

    public static CalcLine of(CommTypeCode commType, Money baseAmount, Rate appliedRate,
                              Money amount, boolean limitIncluded, RoundingPolicy roundingPolicy,
                              Long sourceRateId, String memo) {
        return new CalcLine(commType, baseAmount, appliedRate, amount, Money.ZERO,
                limitIncluded, roundingPolicy, sourceRateId, memo);
    }

    /** 지급률 등 후속 요율 적용으로 금액을 치환한 새 라인. */
    public CalcLine withAmount(Money newAmount, String appendedMemo) {
        return new CalcLine(commType, baseAmount, appliedRate, newAmount, limitCutAmount,
                limitIncluded, roundingPolicy, sourceRateId,
                memo == null ? appendedMemo : memo + "; " + appendedMemo);
    }

    /** 한도 게이트 통과 결과 (지급 가능액 + 삭감/이연액). */
    public CalcLine withLimitCut(Money payable, Money cut, String appendedMemo) {
        return new CalcLine(commType, baseAmount, appliedRate, payable, cut,
                limitIncluded, roundingPolicy, sourceRateId,
                memo == null ? appendedMemo : memo + "; " + appendedMemo);
    }
}
