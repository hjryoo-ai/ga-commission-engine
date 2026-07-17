package ga.comm.limit;

import ga.comm.calc.CalcContext;
import ga.comm.calc.CalculationStep;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.money.Money;
import ga.comm.domain.type.EventType;
import ga.comm.rule.model.LimitRule;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 감액/보험료 변경 → 1200% 한도액 재산정 (설계서 §2.3, §6.1.5).
 *
 * <p>이벤트 속성 {@code new_monthly_premium}으로 변경 후 월납환산보험료를 받는다.
 * 재산정 후 기지급 누적이 신한도를 초과하는 경우의 처리(환수 vs 향후지급 중단)는
 * 유권해석 확인 대상(§11.4)이며, 현재 정책은 "향후지급 중단" — 원장 여유가 0이 되어
 * 게이트가 자연 차단하고, 초과 상태는 리포트로 노출한다.
 */
public class PremiumRepriceStep implements CalculationStep {

    public static final String STEP_ID = "PREMIUM_REPRICE_V1";
    public static final String ATTR_NEW_MONTHLY_PREMIUM = "new_monthly_premium";

    private final LimitLedgerStore ledgerStore;

    public PremiumRepriceStep(LimitLedgerStore ledgerStore) {
        this.ledgerStore = Objects.requireNonNull(ledgerStore);
    }

    @Override
    public String stepId() {
        return STEP_ID;
    }

    @Override
    public boolean supports(CalcContext ctx) {
        return ctx.target().isAgent()
                && ctx.event().eventType() == EventType.REDUCE
                && ctx.event().attributes().containsKey(ATTR_NEW_MONTHLY_PREMIUM);
    }

    @Override
    public void apply(CalcContext ctx) {
        PolicyEvent event = ctx.event();
        Optional<LimitRule> ruleOpt = ctx.rules().limitRule();
        if (ruleOpt.isEmpty()) {
            ctx.trace(STEP_ID, "한도룰 미적용 계약 — 재산정 불필요");
            return;
        }

        Optional<LimitLedger> ledgerOpt = ledgerStore.find(event.policyNo(),
                new AgentId(ctx.target().recipient().id()));
        if (ledgerOpt.isEmpty()) {
            ctx.trace(STEP_ID, "한도 원장이 없어 재산정 생략 (지급 이력 없음)");
            return;
        }

        LimitLedger ledger = ledgerOpt.get();
        Money before = ledger.limitAmount();
        Money newPremium = Money.won(Long.parseLong(
                event.attributes().get(ATTR_NEW_MONTHLY_PREMIUM)));
        ledger.reprice(newPremium);
        ledgerStore.save(ledger);

        boolean breached = ledger.accumPaid().isGreaterThan(ledger.limitAmount());
        ctx.trace(STEP_ID, breached
                        ? "재산정 후 기지급 누적이 신한도 초과 — 향후지급 중단 상태(§11.4 파라미터)"
                        : "한도 재산정 완료",
                Map.of("limitBefore", before.toString(),
                        "limitAfter", ledger.limitAmount().toString(),
                        "accumPaid", ledger.accumPaid().toString(),
                        "newMonthlyPremium", newPremium.toString()));
    }
}
