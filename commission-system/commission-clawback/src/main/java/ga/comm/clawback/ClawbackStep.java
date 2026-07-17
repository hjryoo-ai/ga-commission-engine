package ga.comm.clawback;

import ga.comm.calc.CalcContext;
import ga.comm.calc.CalcLine;
import ga.comm.calc.CalculationStep;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.domain.money.RoundingPolicy;
import ga.comm.domain.type.EventType;
import ga.comm.rule.model.ClawbackTable;
import ga.comm.rule.model.CommTypeAttr;

import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★ 환수 엔진 (설계서 §6.3).
 *
 * <p>환수액 = 순 기지급 수수료(환수 대상 유형만, 기존 환수 반영) × 경과 회차 구간별 환수율
 * (CLAWBACK_RULE, 계약일 기준 버전). 환수는 음수 CLAWBACK 라인으로 생성된다.
 * 환수율은 양수 기준액에 절사 적용 후 부호 반전한다 ({@link RoundingPolicy} 규약).
 *
 * <p>당월 상계·채권화는 마감 단계의 {@link ClawbackOffsetService}가,
 * 한도 원장 차감은 {@link ClawbackPostProcessor}가 수행한다.
 * 조직 오버라이드 환수 여부는 사규 미결정(§11) — 현재는 설계사 본인 지급분만 환수한다.
 */
public class ClawbackStep implements CalculationStep {

    public static final String STEP_ID = "CLAWBACK_V1";

    private final CommCalcStore calcStore;

    public ClawbackStep(CommCalcStore calcStore) {
        this.calcStore = Objects.requireNonNull(calcStore);
    }

    @Override
    public String stepId() {
        return STEP_ID;
    }

    @Override
    public boolean supports(CalcContext ctx) {
        EventType type = ctx.event().eventType();
        return ctx.target().isAgent()
                && (type == EventType.CANCEL || type == EventType.LAPSE || type == EventType.WITHDRAW);
    }

    @Override
    public void apply(CalcContext ctx) {
        PolicyEvent event = ctx.event();

        Optional<ClawbackTable> tableOpt = ctx.rules().clawbackTable(event.eventType());
        if (tableOpt.isEmpty()) {
            ctx.trace(STEP_ID, "환수 룰 없음 — 환수 미발생");
            return;
        }

        int elapsed = elapsedInstallments(event);
        Optional<Rate> rateOpt = tableOpt.get().rateFor(elapsed);
        if (rateOpt.isEmpty()) {
            ctx.trace(STEP_ID, "경과 회차 " + elapsed + " — 환수 구간 밖(환수 없음)");
            return;
        }

        Money netPaid = netClawbackablePaid(ctx);
        if (!netPaid.isPositive()) {
            ctx.trace(STEP_ID, "환수 대상 순 기지급 없음: " + netPaid);
            return;
        }

        CommTypeAttr attr = ctx.rules().commTypeAttr(CommTypeCode.CLAWBACK)
                .orElseThrow(() -> new IllegalStateException("CLAWBACK 유형 속성이 없습니다"));

        Money clawed = netPaid.multiply(rateOpt.get(), attr.roundingPolicy());
        ctx.addLine(CalcLine.of(CommTypeCode.CLAWBACK, netPaid, rateOpt.get(), clawed.negate(),
                attr.limitIncluded(), attr.roundingPolicy(), null,
                event.eventType() + " 환수: 순기지급 " + netPaid + " × " + rateOpt.get()));

        ctx.trace(STEP_ID, "환수 산출", Map.of(
                "eventType", event.eventType().name(),
                "elapsedInstallments", String.valueOf(elapsed),
                "netPaid", netPaid.toString(),
                "clawbackRate", rateOpt.get().toString(),
                "clawedAmount", clawed.negate().toString()));
    }

    /** 경과 회차: 이벤트에 명시된 회차 우선, 없으면 계약일~이벤트일 경과월로 근사. */
    private int elapsedInstallments(PolicyEvent event) {
        if (event.installmentNo() != null) {
            return event.installmentNo();
        }
        return (int) ChronoUnit.MONTHS.between(event.contractDate(), event.eventDate());
    }

    /**
     * 순 기지급 = Σ(환수 대상 유형 지급액) + Σ(기존 CLAWBACK 가감).
     *
     * <p>합산 규약: REVERSED 원본도 합산에 포함한다 — 취소는 원본 제외가 아니라
     * 음수 reversal 레코드가 상쇄하는 방식이다(설계서 §3.2 불변 원장). 기존 환수/부활
     * 재지급도 합산되므로 중복 환수가 발생하지 않는다.
     */
    private Money netClawbackablePaid(CalcContext ctx) {
        Money net = Money.ZERO;
        for (CommCalcRecord record : calcStore.findByPolicyAndRecipient(
                ctx.event().policyNo(), ctx.target().recipient().id())) {
            if (record.commType().equals(CommTypeCode.CLAWBACK)) {
                net = net.plus(record.calcAmount());
                continue;
            }
            boolean clawbackTarget = ctx.rules().commTypeAttr(record.commType())
                    .map(CommTypeAttr::clawbackTarget)
                    .orElse(false);
            if (clawbackTarget) {
                net = net.plus(record.calcAmount());
            }
        }
        return net;
    }
}
