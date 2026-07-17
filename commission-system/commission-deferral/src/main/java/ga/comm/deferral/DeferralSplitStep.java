package ga.comm.deferral;

import ga.comm.calc.CalcContext;
import ga.comm.calc.CalcLine;
import ga.comm.calc.CalculationStep;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.rule.model.DeferralCurve;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ★ 분급 분할 (설계서 §6.2, 2027-01-01부터 Step 설정으로 활성).
 *
 * <p>계약 체결일 기준 유효한 분급 커브(데이터)에 따라 초년도 모집수수료(FY_COMM) 라인을
 * 즉시 지급분(0개월 포인트)과 이연분으로 분할한다. 커브가 없으면(2027 이전 체결) 아무것도 하지 않는다.
 * 4년→7년 확장은 커브 레코드 교체만으로 흡수된다 — 이 클래스는 수정하지 않는다.
 *
 * <p>한도 게이트 이후에 실행된다: 한도는 분할 전 총액으로 소진되고(스케줄 자체가 한도의 산출물),
 * 이연분(DEFERRED)의 도래 지급은 게이트를 다시 타지 않는다.
 *
 * <p>반올림 규약: 각 이연 포인트는 절사 배분하고 마지막 포인트가 잔여를 흡수한다 —
 * 불변식 "분급 스케줄 합 = 이연 원금"(§8.2)이 항상 성립한다.
 * 커브에는 0개월(즉시 지급) 포인트가 있어야 한다 (pct 0 허용, 단 즉시분 0원이면 설정 오류로 거부).
 */
public class DeferralSplitStep implements CalculationStep {

    public static final String STEP_ID = "DEFERRAL_SPLIT_V1";
    public static final String ATTACH_PENDING_SCHEDULES = "deferral.pendingSchedules";

    /** 스케줄 생성 대기 항목 — lineIndex의 라인이 저장되면 그 calcId를 source로 생성한다. */
    public record PendingSchedule(int lineIndex, CloseYm dueYm, Money amount, long curveId) {
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
        Optional<DeferralCurve> curveOpt = ctx.rules().deferralCurve();
        if (curveOpt.isEmpty()) {
            ctx.trace(STEP_ID, "분급 커브 미적용 — 계약체결일 " + ctx.event().contractDate()
                    + " 기준 유효 커브 없음");
            return;
        }
        DeferralCurve curve = curveOpt.get();
        DeferralCurve.CurvePoint immediatePoint = curve.points().stream()
                .filter(p -> p.monthNo() == 0)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "분급 커브에 0개월(즉시 지급) 포인트가 없습니다: " + curve.curveName()));

        CloseYm baseYm = CloseYm.from(ctx.event().eventDate());
        List<PendingSchedule> pending = new ArrayList<>();

        for (int i = 0; i < ctx.lines().size(); i++) {
            CalcLine line = ctx.lines().get(i);
            if (!line.commType().equals(CommTypeCode.FY_COMM) || !line.amount().isPositive()) {
                continue;
            }

            Money total = line.amount();
            Money immediate = total.multiply(immediatePoint.pct().value(), line.roundingPolicy());
            if (immediate.isZero() && total.isPositive()) {
                throw new IllegalStateException(
                        "즉시 지급분이 0원입니다 — 커브 설정 확인: " + curve.curveName());
            }
            Money deferredTotal = total.minus(immediate);

            // 이연 포인트 절사 배분 + 마지막 포인트 잔여 흡수
            List<DeferralCurve.CurvePoint> deferredPoints = curve.points().stream()
                    .filter(p -> p.monthNo() > 0)
                    .sorted((a, b) -> Integer.compare(a.monthNo(), b.monthNo()))
                    .toList();
            Money allocated = Money.ZERO;
            for (int p = 0; p < deferredPoints.size(); p++) {
                DeferralCurve.CurvePoint point = deferredPoints.get(p);
                Money slice = (p == deferredPoints.size() - 1)
                        ? deferredTotal.minus(allocated)
                        : total.multiply(point.pct().value(), line.roundingPolicy());
                allocated = allocated.plus(slice);
                if (slice.isPositive()) {
                    pending.add(new PendingSchedule(i, baseYm.plusMonths(point.monthNo()),
                            slice, curve.curveId()));
                }
            }

            ctx.replaceLine(i, line.withAmount(immediate,
                    "분급 분할(" + curve.curveName() + "): 즉시 " + immediate + " / 이연 " + deferredTotal));
            ctx.trace(STEP_ID, "분급 분할", Map.of(
                    "curve", curve.curveName(),
                    "total", total.toString(),
                    "immediate", immediate.toString(),
                    "deferred", deferredTotal.toString(),
                    "points", String.valueOf(deferredPoints.size())));
        }

        if (!pending.isEmpty()) {
            ctx.putAttachment(ATTACH_PENDING_SCHEDULES, pending);
        }
    }
}
