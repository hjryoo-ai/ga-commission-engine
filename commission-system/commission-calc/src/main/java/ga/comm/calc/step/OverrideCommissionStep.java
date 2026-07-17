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
import ga.comm.rule.model.OrgOverrideRate;

import java.util.Map;
import java.util.Optional;

/**
 * 조직 오버라이드 수수료 (ORG 수급자 라인).
 * 기준액 = 해당 이벤트의 원 수수료(지급률 적용 전, 산출기준액×요율),
 * 금액 = 기준액 × 조직 계층별 오버라이드 배분율.
 * 배분율 미설정 계층은 라인을 만들지 않는다.
 */
public class OverrideCommissionStep implements CalculationStep {

    public static final String STEP_ID = "OVERRIDE_COMM_V1";

    @Override
    public String stepId() {
        return STEP_ID;
    }

    @Override
    public boolean supports(CalcContext ctx) {
        EventType type = ctx.event().eventType();
        return !ctx.target().isAgent() && (type == EventType.NEW || type == EventType.PAYMENT);
    }

    @Override
    public void apply(CalcContext ctx) {
        PolicyEvent event = ctx.event();

        CommRateRule rateRule;
        CommTypeCode baseCommType;
        Money base;

        if (event.eventType() == EventType.NEW) {
            baseCommType = CommTypeCode.FY_COMM;
            base = event.monthlyPremium();
            rateRule = ctx.rules().outboundRate(CommTypeCode.FY_COMM, null)
                    .orElseThrow(() -> new RuleNotFoundException(
                            "신계약 성립 요율이 없습니다: " + event.productKey()));
        } else {
            Integer installment = event.installmentNo();
            base = event.paymentAmount() != null ? event.paymentAmount() : event.monthlyPremium();
            Optional<CommRateRule> fy = ctx.rules().outboundRate(CommTypeCode.FY_COMM, installment);
            if (fy.isPresent()) {
                baseCommType = CommTypeCode.FY_COMM;
                rateRule = fy.get();
            } else {
                Optional<CommRateRule> renewal = ctx.rules().outboundRate(CommTypeCode.RENEWAL, installment);
                if (renewal.isEmpty()) {
                    ctx.trace(STEP_ID, "회차 요율 없음 — 오버라이드 미발생");
                    return;
                }
                baseCommType = CommTypeCode.RENEWAL;
                rateRule = renewal.get();
            }
        }

        Optional<OrgOverrideRate> overrideRate =
                ctx.rules().orgOverrideRate(ctx.target().orgLevel(), baseCommType);
        if (overrideRate.isEmpty()) {
            ctx.trace(STEP_ID, "오버라이드 배분율 미설정: " + ctx.target().orgLevel() + "/" + baseCommType);
            return;
        }

        CommTypeAttr attr = ctx.rules().commTypeAttr(CommTypeCode.OVERRIDE)
                .orElseThrow(() -> new RuleNotFoundException("수수료 유형 속성이 없습니다: OVERRIDE"));

        Money rawCommission = base.multiply(rateRule.rate(), attr.roundingPolicy());
        Money amount = rawCommission.multiply(overrideRate.get().overrideRate(), attr.roundingPolicy());
        ctx.addLine(CalcLine.of(CommTypeCode.OVERRIDE, rawCommission, overrideRate.get().overrideRate(),
                amount, attr.limitIncluded(), attr.roundingPolicy(), rateRule.rateId(),
                ctx.target().orgLevel() + " 오버라이드 " + rawCommission + "×" + overrideRate.get().overrideRate()));

        ctx.trace(STEP_ID, "오버라이드 산출", Map.of(
                "orgLevel", ctx.target().orgLevel().name(),
                "baseCommission", rawCommission.toString(),
                "overrideRate", overrideRate.get().overrideRate().toString(),
                "amount", amount.toString()));
    }
}
