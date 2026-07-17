package ga.comm.calc.step;

import ga.comm.calc.CalcContext;
import ga.comm.calc.CalcLine;
import ga.comm.calc.CalculationStep;
import ga.comm.calc.RuleVersionRef;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.domain.type.EventType;
import ga.comm.rule.IncentiveRepository;
import ga.comm.rule.incentive.IncentiveConditionEvaluator;
import ga.comm.rule.incentive.IncentiveConditionInput;
import ga.comm.rule.model.CommTypeAttr;
import ga.comm.rule.model.IncentiveRule;
import ga.comm.rule.RuleNotFoundException;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 시책 계산 V2 (설계서 §3.6, Phase 13) — 시책 조건을 <b>데이터(INCENTIVE_MST)로</b> 판정한다.
 *
 * <p>V1({@link IncentiveStep})은 조건이 외부에서 판정되어 이벤트 속성으로 전달된 금액을 편입했다.
 * V2는 기준일 유효 ACTIVE 시책 전부를 대상 필터로 추린 뒤, 각 조건식을 <b>샌드박스</b>에서 평가하고
 * (§보안·결정론), 충족분마다 산식으로 금액을 산출해 라인으로 편입한다. 시책은 한도 포함 유형이므로
 * 순서는 IncentiveV2(30) &lt; LimitGate(50)로 두어 한도 게이트 앞에서 라인이 만들어진다.
 *
 * <p>기존 Step 무수정 규약(부록 B-12): V1은 존치하고 V2는 신규 등록·활성 전환한다.
 *
 * <p>결정론/재현성: 조건 입력은 이벤트/설계사/사전 집계 실적 스냅샷뿐이고, 사용된 시책 버전은
 * {@code rule_versions}에 박제되며, 모든 조건 판정(어떤 시책·참/거짓·금액)은 {@code calc_trace}에 남는다.
 */
public class IncentiveV2Step implements CalculationStep {

    public static final String STEP_ID = "INCENTIVE_V2";
    public static final String ATTR_CHANNEL = "channel";
    public static final String ATTR_PERF_FYC = "perf.fycSum";
    public static final String ATTR_PERF_COUNT = "perf.contractCount";
    public static final String ATTR_PERF_PERSISTENCY = "perf.persistencyBp";

    private final IncentiveRepository incentives;
    private final IncentiveConditionEvaluator evaluator;

    public IncentiveV2Step(IncentiveRepository incentives, IncentiveConditionEvaluator evaluator) {
        this.incentives = Objects.requireNonNull(incentives);
        this.evaluator = Objects.requireNonNull(evaluator);
    }

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
        List<IncentiveRule> candidates = incentives.findActiveAt(event.eventDate()).stream()
                .filter(inc -> inc.matchesTarget(event.insurerCd(), event.productKey(),
                        event.attributes().get(ATTR_CHANNEL)))
                .toList();
        if (candidates.isEmpty()) {
            return;
        }

        // 시책 유형 속성(한도 포함/반올림)은 대상 시책이 있을 때만 필요 — 없으면 fail-fast.
        CommTypeAttr attr = ctx.rules().commTypeAttr(CommTypeCode.INCENTIVE)
                .orElseThrow(() -> new RuleNotFoundException(
                        "수수료 유형 속성이 없습니다: INCENTIVE 기준일=" + event.eventDate()));

        IncentiveConditionInput input = inputOf(ctx);
        for (IncentiveRule inc : candidates) {
            // 평가 오류는 던진다(계산 거부, 침묵 스킵 금지). 결과가 false면 라인 없음.
            boolean matched = evaluator.matches(inc.conditionExpr(), input);
            if (!matched) {
                ctx.trace(STEP_ID, "시책 미해당: " + inc.incentiveCd(),
                        Map.of("condition", inc.conditionExpr(), "matched", "false"));
                continue;
            }

            Money base;
            Rate appliedRate;
            Money amount;
            switch (inc.payoutKind()) {
                case FIXED -> {
                    base = inc.fixedAmount();
                    appliedRate = null;
                    amount = inc.fixedAmount();
                }
                case PREMIUM_RATE -> {
                    base = event.monthlyPremium();
                    appliedRate = inc.premiumRate();
                    amount = base.multiply(inc.premiumRate(), attr.roundingPolicy());
                }
                default -> throw new IllegalStateException("알 수 없는 산식: " + inc.payoutKind());
            }

            ctx.addLine(CalcLine.of(CommTypeCode.INCENTIVE, base, appliedRate, amount,
                    attr.limitIncluded(), attr.roundingPolicy(), inc.incentiveId(),
                    "시책 " + inc.incentiveCd() + " (" + inc.payoutKind() + ")"));

            // 재현성: 실제 사용된 시책 버전을 rule_versions에 박제 (§6.5 replay)
            ctx.rules().recordRuleVersion(new RuleVersionRef("INCENTIVE_MST", inc.incentiveCd(),
                    "incentiveId=" + inc.incentiveId() + ",v=" + inc.versionNo()));

            ctx.trace(STEP_ID, "시책 편입: " + inc.incentiveCd(), Map.of(
                    "condition", inc.conditionExpr(),
                    "matched", "true",
                    "payoutKind", inc.payoutKind().name(),
                    "amount", amount.toString(),
                    "limitIncluded", String.valueOf(attr.limitIncluded())));
        }
    }

    private IncentiveConditionInput inputOf(CalcContext ctx) {
        PolicyEvent e = ctx.event();
        Map<String, String> a = e.attributes();
        return IncentiveConditionInput.builder()
                .premium(e.monthlyPremium().toLong())
                .insurerCd(e.insurerCd().value())
                .productKey(e.productKey().value())
                .channel(a.get(ATTR_CHANNEL))
                .eventType(e.eventType().name())
                .gradeCd(ctx.target().gradeCd())
                .agentId(e.agentId() == null ? null : e.agentId().value())
                .fycSum(parseLong(a.get(ATTR_PERF_FYC)))
                .contractCount((int) parseLong(a.get(ATTR_PERF_COUNT)))
                .persistencyBp((int) parseLong(a.get(ATTR_PERF_PERSISTENCY)))
                .build();
    }

    private static long parseLong(String value) {
        return value == null || value.isBlank() ? 0L : Long.parseLong(value.trim());
    }
}
