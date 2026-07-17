package ga.comm.api.web;

import ga.comm.api.SimulationService;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 시뮬레이션 컨트롤러 (설계서 §10 Phase 12) — {@link SimulationService} 위에 얇게.
 *
 * <p>요청 금액은 long(원), 계약일은 ISO(yyyy-MM-dd). 시뮬레이션 라인은 저장 원장이 아니라
 * 사전 투영값이므로(REVERSED 상태 없음) 합산 규약(§3.0)의 대상이 아니다.
 */
@RestController
@RequestMapping("/api/commissions")
public class SimulationController {

    private final SimulationService simulationService;

    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping("/simulate")
    public SimulationResponse simulate(@RequestBody SimulationRequest request) {
        SimulationService.SimulationResult result = simulationService.simulate(request.toDomain());
        return SimulationResponse.from(result);
    }

    public record SimulationRequest(
            String insurerCd,
            String productKey,
            LocalDate contractDate,
            long monthlyPremium,
            String gradeCd,
            Long plannedIncentive
    ) {
        SimulationService.SimulationRequest toDomain() {
            return new SimulationService.SimulationRequest(
                    new InsurerCode(insurerCd),
                    new ProductKey(productKey),
                    contractDate,
                    Money.won(monthlyPremium),
                    gradeCd,
                    plannedIncentive == null ? null : Money.won(plannedIncentive));
        }
    }

    public record SimulationResponse(
            List<Line> lines,
            long firstYearTotal,
            long limitIncludedTotal,
            Long limitAmount,
            long overLimitAmount,
            BigDecimal utilizationPct,
            boolean deferralApplies,
            long immediateFirstYear
    ) {
        static SimulationResponse from(SimulationService.SimulationResult r) {
            return new SimulationResponse(
                    r.lines().stream().map(Line::from).toList(),
                    r.firstYearTotal().toLong(),
                    r.limitIncludedTotal().toLong(),
                    r.limitAmount() == null ? null : r.limitAmount().toLong(),
                    r.overLimitAmount().toLong(),
                    r.utilizationPct(),
                    r.deferralApplies(),
                    r.immediateFirstYear().toLong());
        }
    }

    public record Line(String commType, Integer installmentNo, long amount, boolean limitIncluded) {
        static Line from(SimulationService.SimulationLine l) {
            return new Line(l.commType().value(), l.installmentNo(), l.amount().toLong(),
                    l.limitIncluded());
        }
    }
}
