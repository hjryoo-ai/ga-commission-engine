package ga.comm.api;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.RoundingPolicy;
import ga.comm.domain.type.Direction;
import ga.comm.rule.RuleNotFoundException;
import ga.comm.rule.RuleRepository;
import ga.comm.rule.model.ChannelType;
import ga.comm.rule.model.CommRateRule;
import ga.comm.rule.model.CommTypeAttr;
import ga.comm.rule.model.DeferralCurve;
import ga.comm.rule.model.LimitRule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 수수료 시뮬레이션 (설계서 §10 Phase 9):
 * "이 계약 체결 시 초년도 예상 수수료 / 1200% 한도 소진 / 분급 즉시지급분"을 사전 계산한다.
 * 저장 없이 룰 조회만 사용하며, 룰 조회 규약(기준일 필수)을 그대로 따른다.
 */
public class SimulationService {

    private final RuleRepository rules;

    public SimulationService(RuleRepository rules) {
        this.rules = Objects.requireNonNull(rules);
    }

    public record SimulationRequest(
            InsurerCode insurerCd,
            ProductKey productKey,
            LocalDate contractDate,
            Money monthlyPremium,
            String gradeCd,
            Money plannedIncentive   // null 허용
    ) {
    }

    public record SimulationLine(CommTypeCode commType, Integer installmentNo,
                                 Money amount, boolean limitIncluded) {
    }

    /**
     * @param limitAmount       1200% 한도액 (해당 계약 미적용이면 null)
     * @param overLimitAmount   한도 초과 예상액 (삭감/이연 예상분)
     * @param utilizationPct    한도 소진율 % (정보성, scale 2)
     * @param immediateFirstYear 분급 반영 시 초년도 즉시 수령 예상액
     */
    public record SimulationResult(
            List<SimulationLine> lines,
            Money firstYearTotal,
            Money limitIncludedTotal,
            Money limitAmount,
            Money overLimitAmount,
            BigDecimal utilizationPct,
            boolean deferralApplies,
            Money immediateFirstYear
    ) {
    }

    public SimulationResult simulate(SimulationRequest request) {
        List<SimulationLine> lines = new ArrayList<>();

        // 신계약 성립 수수료
        lines.add(fyLine(request, null, request.contractDate(), request.monthlyPremium()));

        // 초년도 2~12회차 (도래 예정일 기준일로 요율 조회)
        for (int inst = 2; inst <= 12; inst++) {
            LocalDate dueDate = request.contractDate().plusMonths(inst - 1L);
            Optional<CommRateRule> rate = rules.findRate(Direction.OUTBOUND, request.insurerCd(),
                    request.productKey(), CommTypeCode.FY_COMM, inst, dueDate);
            if (rate.isEmpty()) {
                continue;  // 회차 요율 미등록 상품
            }
            lines.add(fyLine(request, inst, dueDate, request.monthlyPremium()));
        }

        // 예정 시책
        if (request.plannedIncentive() != null && request.plannedIncentive().isPositive()) {
            CommTypeAttr attr = attr(CommTypeCode.INCENTIVE, request.contractDate());
            Money payout = request.plannedIncentive().multiply(
                    payoutRate(request.gradeCd(), CommTypeCode.INCENTIVE, request.contractDate()),
                    attr.roundingPolicy());
            lines.add(new SimulationLine(CommTypeCode.INCENTIVE, null, payout, attr.limitIncluded()));
        }

        Money firstYearTotal = lines.stream().map(SimulationLine::amount)
                .reduce(Money.ZERO, Money::plus);
        Money limitIncludedTotal = lines.stream().filter(SimulationLine::limitIncluded)
                .map(SimulationLine::amount).reduce(Money.ZERO, Money::plus);

        Optional<LimitRule> limitRule = rules.findLimitRule(ChannelType.GA_TO_AGENT,
                request.contractDate());
        Money limitAmount = limitRule
                .map(r -> request.monthlyPremium().multiply(r.limitMultiple(), RoundingPolicy.KRW_FLOOR))
                .orElse(null);
        Money overLimit = limitAmount == null
                ? Money.ZERO
                : limitIncludedTotal.minus(limitAmount).max(Money.ZERO);
        BigDecimal utilization = limitAmount == null || limitAmount.isZero()
                ? null
                : limitIncludedTotal.asBigDecimal()
                .multiply(BigDecimal.valueOf(100))
                .divide(limitAmount.asBigDecimal(), 2, RoundingMode.HALF_UP);

        // 분급: FY_COMM 라인만 즉시지급 비율 적용 (설계서 §6.2)
        Optional<DeferralCurve> curve = rules.findDeferralCurve(request.contractDate());
        Money immediate;
        if (curve.isPresent()) {
            var pct0 = curve.get().points().stream().filter(p -> p.monthNo() == 0).findFirst()
                    .orElseThrow(() -> new IllegalStateException("커브에 0개월 포인트가 없습니다"));
            Money immediateFy = Money.ZERO;
            Money nonFy = Money.ZERO;
            for (SimulationLine line : lines) {
                if (line.commType().equals(CommTypeCode.FY_COMM)) {
                    immediateFy = immediateFy.plus(
                            line.amount().multiply(pct0.pct().value(), RoundingPolicy.KRW_FLOOR));
                } else {
                    nonFy = nonFy.plus(line.amount());
                }
            }
            immediate = immediateFy.plus(nonFy);
        } else {
            immediate = firstYearTotal;
        }

        return new SimulationResult(List.copyOf(lines), firstYearTotal, limitIncludedTotal,
                limitAmount, overLimit, utilization, curve.isPresent(), immediate);
    }

    private SimulationLine fyLine(SimulationRequest request, Integer installmentNo,
                                  LocalDate baseDate, Money base) {
        CommRateRule rate = rules.findRate(Direction.OUTBOUND, request.insurerCd(),
                        request.productKey(), CommTypeCode.FY_COMM, installmentNo, baseDate)
                .orElseThrow(() -> new RuleNotFoundException(
                        "요율이 없습니다: FY_COMM 회차=" + installmentNo + " 기준일=" + baseDate));
        CommTypeAttr attr = attr(CommTypeCode.FY_COMM, baseDate);
        Money amount = base.multiply(rate.rate(), attr.roundingPolicy())
                .multiply(payoutRate(request.gradeCd(), CommTypeCode.FY_COMM, baseDate),
                        attr.roundingPolicy());
        return new SimulationLine(CommTypeCode.FY_COMM, installmentNo, amount, attr.limitIncluded());
    }

    private CommTypeAttr attr(CommTypeCode type, LocalDate baseDate) {
        return rules.findCommTypeAttr(type, baseDate)
                .orElseThrow(() -> new RuleNotFoundException("유형 속성이 없습니다: " + type));
    }

    private ga.comm.domain.money.Rate payoutRate(String gradeCd, CommTypeCode type, LocalDate baseDate) {
        return rules.findPayoutRate(gradeCd, type, baseDate)
                .orElseThrow(() -> new RuleNotFoundException(
                        "지급률이 없습니다: " + gradeCd + "/" + type))
                .payoutRate();
    }
}
