package ga.comm.clawback;

import ga.comm.calc.CalcContext;
import ga.comm.calc.CalcLine;
import ga.comm.calc.CalculationStep;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Money;
import ga.comm.domain.type.EventType;
import ga.comm.rule.model.CommTypeAttr;

import java.util.Map;
import java.util.Objects;

/**
 * 부활(REVIVE) 처리 — 환수분 재지급 여부는 사규 파라미터 (§11.5).
 * REPAY 정책이면 순 환수 잔액(Σ CLAWBACK, 음수)을 양수 CLAWBACK 라인으로 되돌린다.
 */
public class ReviveStep implements CalculationStep {

    public static final String STEP_ID = "REVIVE_V1";

    /** 부활 시 환수분 재지급 정책 (사규 파라미터). */
    public enum RevivePolicy {
        REPAY,
        NO_REPAY
    }

    private final CommCalcStore calcStore;
    private final RevivePolicy policy;

    public ReviveStep(CommCalcStore calcStore, RevivePolicy policy) {
        this.calcStore = Objects.requireNonNull(calcStore);
        this.policy = Objects.requireNonNull(policy);
    }

    @Override
    public String stepId() {
        return STEP_ID;
    }

    @Override
    public boolean supports(CalcContext ctx) {
        return ctx.target().isAgent() && ctx.event().eventType() == EventType.REVIVE;
    }

    @Override
    public void apply(CalcContext ctx) {
        if (policy == RevivePolicy.NO_REPAY) {
            ctx.trace(STEP_ID, "부활 재지급 정책 NO_REPAY — 재지급 없음");
            return;
        }

        // 합산 규약: REVERSED 원본 포함, reversal 레코드가 음수로 상쇄 (§3.2)
        Money netClawed = Money.ZERO;
        for (CommCalcRecord record : calcStore.findByPolicyAndRecipient(
                ctx.event().policyNo(), ctx.target().recipient().id())) {
            if (record.commType().equals(CommTypeCode.CLAWBACK)) {
                netClawed = netClawed.plus(record.calcAmount());
            }
        }
        if (!netClawed.isNegative()) {
            ctx.trace(STEP_ID, "재지급할 환수 잔액 없음: " + netClawed);
            return;
        }

        Money repay = netClawed.negate();
        CommTypeAttr attr = ctx.rules().commTypeAttr(CommTypeCode.CLAWBACK)
                .orElseThrow(() -> new IllegalStateException("CLAWBACK 유형 속성이 없습니다"));

        ctx.addLine(CalcLine.of(CommTypeCode.CLAWBACK, repay, null, repay,
                attr.limitIncluded(), attr.roundingPolicy(), null,
                "부활 — 환수분 재지급 " + repay));
        ctx.trace(STEP_ID, "부활 재지급", Map.of("repay", repay.toString()));
    }
}
