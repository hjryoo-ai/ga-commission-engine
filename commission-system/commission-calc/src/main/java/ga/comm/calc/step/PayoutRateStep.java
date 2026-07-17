package ga.comm.calc.step;

import ga.comm.calc.CalcContext;
import ga.comm.calc.CalcLine;
import ga.comm.calc.CalculationStep;
import ga.comm.domain.money.Money;
import ga.comm.rule.RuleNotFoundException;
import ga.comm.rule.model.PayoutRateRule;

import java.util.Map;

/**
 * 직급/등급 지급률 적용 — 이 Step 실행 시점까지 쌓인 설계사 라인(기본/계속 수수료)에 적용한다.
 * 시책 등 이후 Step이 만드는 라인은 각 Step이 자체적으로 지급률을 반영한다.
 */
public class PayoutRateStep implements CalculationStep {

    public static final String STEP_ID = "PAYOUT_RATE_V1";

    @Override
    public String stepId() {
        return STEP_ID;
    }

    @Override
    public boolean supports(CalcContext ctx) {
        return ctx.target().isAgent() && !ctx.lines().isEmpty();
    }

    @Override
    public void apply(CalcContext ctx) {
        String grade = ctx.target().gradeCd();
        for (int i = 0; i < ctx.lines().size(); i++) {
            CalcLine line = ctx.lines().get(i);
            PayoutRateRule payout = ctx.rules().payoutRate(grade, line.commType())
                    .orElseThrow(() -> new RuleNotFoundException(
                            "지급률이 없습니다: 등급=" + grade + " 유형=" + line.commType()
                                    + " 기준일=" + ctx.event().eventDate()));

            Money after = line.amount().multiply(payout.payoutRate(), line.roundingPolicy());
            ctx.replaceLine(i, line.withAmount(after, "지급률 " + payout.payoutRate() + " 적용"));

            ctx.trace(STEP_ID, "지급률 적용", Map.of(
                    "commType", line.commType().value(),
                    "grade", grade,
                    "payoutRate", payout.payoutRate().toString(),
                    "before", line.amount().toString(),
                    "after", after.toString()));
        }
    }
}
