package ga.comm.calc.step;

import ga.comm.calc.CalcContext;
import ga.comm.calc.CalcLine;
import ga.comm.calc.CalculationStep;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Money;
import ga.comm.domain.type.EventType;
import ga.comm.rule.RuleNotFoundException;
import ga.comm.rule.model.CommRateRule;
import ga.comm.rule.model.CommTypeAttr;

import java.util.Map;
import java.util.Optional;

/**
 * 기본 수수료 = 산출기준액 × 요율 (설계사 본인 라인).
 *
 * <p>NEW: 신계약 성립 수수료 — FY_COMM의 회차 무관(installment null) 요율.
 * PAYMENT: 회차 요율 — FY_COMM(초년도 회차)에서 먼저 찾고 없으면 RENEWAL(계속수수료)로 폴백.
 * 어느 유형에 요율이 등록돼 있는지가 곧 초년도/계속의 구분이다 (룰은 데이터).
 */
public class BaseCommissionStep implements CalculationStep {

    public static final String STEP_ID = "BASE_COMM_V1";

    @Override
    public String stepId() {
        return STEP_ID;
    }

    @Override
    public boolean supports(CalcContext ctx) {
        EventType type = ctx.event().eventType();
        return ctx.target().isAgent() && (type == EventType.NEW || type == EventType.PAYMENT);
    }

    @Override
    public void apply(CalcContext ctx) {
        PolicyEvent event = ctx.event();

        CommRateRule rateRule;
        CommTypeCode commType;
        Money base;

        if (event.eventType() == EventType.NEW) {
            commType = CommTypeCode.FY_COMM;
            base = event.monthlyPremium();
            rateRule = ctx.rules().outboundRate(CommTypeCode.FY_COMM, null)
                    .orElseThrow(() -> new RuleNotFoundException(
                            "신계약 성립 요율이 없습니다: " + event.productKey() + " 기준일=" + event.eventDate()));
        } else {
            Integer installment = event.installmentNo();
            if (installment == null) {
                throw new IllegalStateException("PAYMENT 이벤트에 회차가 없습니다: " + event.eventKey());
            }
            base = event.paymentAmount() != null ? event.paymentAmount() : event.monthlyPremium();

            Optional<CommRateRule> fy = ctx.rules().outboundRate(CommTypeCode.FY_COMM, installment);
            if (fy.isPresent()) {
                commType = CommTypeCode.FY_COMM;
                rateRule = fy.get();
            } else {
                commType = CommTypeCode.RENEWAL;
                rateRule = ctx.rules().outboundRate(CommTypeCode.RENEWAL, installment)
                        .orElseThrow(() -> new RuleNotFoundException(
                                "회차 요율이 없습니다: " + event.productKey() + " 회차=" + installment
                                        + " 기준일=" + event.eventDate()));
            }
        }

        CommTypeAttr attr = ctx.rules().commTypeAttr(commType)
                .orElseThrow(() -> new RuleNotFoundException(
                        "수수료 유형 속성이 없습니다: " + commType + " 기준일=" + event.eventDate()));

        Money amount = base.multiply(rateRule.rate(), attr.roundingPolicy());
        ctx.addLine(CalcLine.of(commType, base, rateRule.rate(), amount,
                attr.limitIncluded(), attr.roundingPolicy(), rateRule.rateId(),
                "기본 수수료 " + base + "×" + rateRule.rate()));

        ctx.trace(STEP_ID, "기본 수수료 산출", Map.of(
                "commType", commType.value(),
                "base", base.toString(),
                "rate", rateRule.rate().toString(),
                "amount", amount.toString(),
                "rateId", String.valueOf(rateRule.rateId())));
    }
}
