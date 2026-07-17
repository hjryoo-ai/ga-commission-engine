package ga.comm.limit;

import ga.comm.calc.CalcContext;
import ga.comm.calc.CalcLine;
import ga.comm.calc.CalculationStep;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.money.Money;
import ga.comm.rule.model.LimitRule;
import ga.comm.rule.model.OverLimitAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★ 1200% 한도 게이트 (설계서 §6.1) — 사후 리포트가 아니라 지급 파이프라인의 게이트.
 *
 * <p>처리 순서:
 * ① 계약 체결일로 LimitRule 버전 조회 (없으면 미적용 — 2026-06-30 이전 체결 등)
 * ② (policy_no, agent_id) 원장 확보
 * ③ 한도 포함(limitIncluded) 라인만 합산 대상
 * ④ available = limit − accum, 초과분은 룰의 OverLimitAction에 따라 삭감(CUT) 또는 이연(DEFER_AFTER_FY)
 *
 * <p>원장 전기는 calcId 확정 후 {@link LimitLedgerPoster}가 수행한다. 같은 컨텍스트 안의
 * 후속 라인은 선행 라인의 예약분을 차감한 잔여 한도를 본다.
 *
 * <p>수급자 범위: 설계사 본인 라인만. 조직 오버라이드의 한도 합산 여부는 유권해석 대상(§11.2)이며,
 * 포함으로 결정되면 OVERRIDE 유형의 limit_included 플래그를 Y로 바꾸고 이 Step의 supports를
 * 확장한 V2를 등록한다.
 */
public class LimitGateStep implements CalculationStep {

    public static final String STEP_ID = "LIMIT_GATE_V1";
    public static final String ATTACH_LEDGER = "limit.ledger";
    public static final String ATTACH_PENDING_POSTS = "limit.pendingPosts";

    /** 원장 전기 대기 항목 — lineIndex의 라인이 저장되면 그 calcId로 전기한다. */
    public record PendingPost(int lineIndex, Money amount) {
    }

    private final LimitLedgerStore ledgerStore;

    public LimitGateStep(LimitLedgerStore ledgerStore) {
        this.ledgerStore = Objects.requireNonNull(ledgerStore);
    }

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
        PolicyEvent event = ctx.event();

        Optional<LimitRule> ruleOpt = ctx.rules().limitRule();
        if (ruleOpt.isEmpty()) {
            ctx.trace(STEP_ID, "한도룰 미적용 — 계약체결일 " + event.contractDate() + " 기준 유효 버전 없음");
            return;
        }
        LimitRule rule = ruleOpt.get();

        LimitLedger ledger = ledgerStore.getOrCreate(event.policyNo(),
                new AgentId(ctx.target().recipient().id()),
                event.contractDate(), event.monthlyPremium(), rule);
        ctx.attachLimitView(ledger);
        ctx.putAttachment(ATTACH_LEDGER, ledger);

        if (!ledger.inFirstYearWindow(event.eventDate())) {
            ctx.trace(STEP_ID, "초년도 윈도우(" + ledger.fyStart() + "~" + ledger.fyEnd()
                    + ") 밖 — 게이트 미적용");
            return;
        }

        List<PendingPost> pending = new ArrayList<>();
        Money reserved = Money.ZERO;

        for (int i = 0; i < ctx.lines().size(); i++) {
            CalcLine line = ctx.lines().get(i);
            if (!line.limitIncluded() || !line.amount().isPositive()) {
                continue;
            }

            Money available = ledger.limitAmount().minus(ledger.accumPaid()).minus(reserved)
                    .max(Money.ZERO);
            Money payable = line.amount().min(available);
            Money cut = line.amount().minus(payable);

            if (cut.isPositive()) {
                String action = rule.overLimitAction() == OverLimitAction.CUT
                        ? "초과분 삭감(CUT)"
                        : "초과분 초년도 종료 후 이연(DEFER_AFTER_FY)";
                ctx.replaceLine(i, line.withLimitCut(payable, cut,
                        "1200% 한도 게이트: " + action + " " + cut));
                ctx.trace(STEP_ID, "한도 초과 — " + action, Map.of(
                        "commType", line.commType().value(),
                        "requested", line.amount().toString(),
                        "available", available.toString(),
                        "payable", payable.toString(),
                        "cut", cut.toString(),
                        "action", rule.overLimitAction().name()));
            } else {
                ctx.trace(STEP_ID, "한도 통과", Map.of(
                        "commType", line.commType().value(),
                        "amount", line.amount().toString(),
                        "available", available.toString()));
            }

            if (payable.isPositive()) {
                pending.add(new PendingPost(i, payable));
                reserved = reserved.plus(payable);
            }
        }

        if (!pending.isEmpty()) {
            ctx.putAttachment(ATTACH_PENDING_POSTS, pending);
        }
    }
}
