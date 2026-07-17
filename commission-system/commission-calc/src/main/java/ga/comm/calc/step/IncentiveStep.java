package ga.comm.calc.step;

import ga.comm.calc.CalcContext;
import ga.comm.calc.CalcLine;
import ga.comm.calc.CalculationStep;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.domain.type.EventType;
import ga.comm.rule.RuleNotFoundException;
import ga.comm.rule.model.CommTypeAttr;

import java.util.Map;

/**
 * 시책/프로모션 계산.
 *
 * <p>시책 조건 판정(기간·상품·실적)은 시책 엔진의 몫이고, 이 Step은 조건 충족이 확정되어
 * 이벤트 속성({@code incentive_amount})으로 전달된 시책 금액을 계산 라인으로 편입한다.
 * 시책은 2026.7~ 1200% 한도 합산 대상이므로(COMM_TYPE_MST 데이터) 한도 게이트 앞에서 라인이 만들어져야 한다.
 */
public class IncentiveStep implements CalculationStep {

    public static final String STEP_ID = "INCENTIVE_V1";
    public static final String ATTR_INCENTIVE_AMOUNT = "incentive_amount";

    @Override
    public String stepId() {
        return STEP_ID;
    }

    @Override
    public boolean supports(CalcContext ctx) {
        EventType type = ctx.event().eventType();
        return ctx.target().isAgent()
                && (type == EventType.NEW || type == EventType.PAYMENT)
                && ctx.event().attributes().containsKey(ATTR_INCENTIVE_AMOUNT);
    }

    @Override
    public void apply(CalcContext ctx) {
        Money incentive = Money.won(Long.parseLong(
                ctx.event().attributes().get(ATTR_INCENTIVE_AMOUNT)));
        if (incentive.isZero()) {
            return;
        }

        CommTypeAttr attr = ctx.rules().commTypeAttr(CommTypeCode.INCENTIVE)
                .orElseThrow(() -> new RuleNotFoundException(
                        "수수료 유형 속성이 없습니다: INCENTIVE 기준일=" + ctx.event().eventDate()));
        Rate payout = ctx.rules().payoutRate(ctx.target().gradeCd(), CommTypeCode.INCENTIVE)
                .orElseThrow(() -> new RuleNotFoundException(
                        "지급률이 없습니다: 등급=" + ctx.target().gradeCd() + " 유형=INCENTIVE"))
                .payoutRate();

        Money amount = incentive.multiply(payout, attr.roundingPolicy());
        ctx.addLine(CalcLine.of(CommTypeCode.INCENTIVE, incentive, payout, amount,
                attr.limitIncluded(), attr.roundingPolicy(), null,
                "시책 " + incentive + "×지급률 " + payout));

        ctx.trace(STEP_ID, "시책 편입", Map.of(
                "incentive", incentive.toString(),
                "payoutRate", payout.toString(),
                "amount", amount.toString(),
                "limitIncluded", String.valueOf(attr.limitIncluded())));
    }
}
