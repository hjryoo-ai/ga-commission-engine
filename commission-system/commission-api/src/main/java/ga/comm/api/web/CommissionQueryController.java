package ga.comm.api.web;

import ga.comm.api.CommissionQueryService;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.deferral.ScheduleEntry;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 조회 컨트롤러 (설계서 §10 Phase 12) — {@link CommissionQueryService} 파사드 위에 얇게.
 *
 * <p>순액(netAmount)은 파사드가 {@code NetAmountCalculator}를 경유해 산출한 값을 그대로 노출한다.
 * 컨트롤러는 금액을 직접 합산하지 않는다(§3.0 합산 규약 — 상태 필터 SUM 금지, 부록 B-4).
 */
@RestController
@RequestMapping("/api/commissions")
public class CommissionQueryController {

    private final CommissionQueryService queryService;

    public CommissionQueryController(CommissionQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/policies/{policyNo}/agents/{agentId}")
    public PolicyCommissionResponse policyView(@PathVariable String policyNo,
                                               @PathVariable String agentId) {
        CommissionQueryService.PolicyCommissionView view =
                queryService.policyView(new PolicyNo(policyNo), new AgentId(agentId));
        return PolicyCommissionResponse.from(policyNo, agentId, view);
    }

    /** 금액은 전부 long(원). netAmount는 파사드가 준 순액을 그대로 담는다. */
    public record PolicyCommissionResponse(
            String policyNo,
            String agentId,
            long netAmount,
            List<CalcLine> records,
            LimitSummary limit,
            List<DeferralSchedule> deferralSchedules
    ) {
        static PolicyCommissionResponse from(String policyNo, String agentId,
                                             CommissionQueryService.PolicyCommissionView view) {
            return new PolicyCommissionResponse(
                    policyNo,
                    agentId,
                    view.netAmount().toLong(),
                    view.records().stream().map(CalcLine::from).toList(),
                    view.limit().map(LimitSummary::from).orElse(null),
                    view.deferralSchedules().stream().map(DeferralSchedule::from).toList());
        }
    }

    /** 개별 레코드 표시용 — 여기의 calcAmount는 '표시'이며 순액 산출에 쓰지 않는다. */
    public record CalcLine(long calcId, String commType, String closeYm,
                           long calcAmount, String status) {
        static CalcLine from(CommCalcRecord r) {
            return new CalcLine(r.calcId(), r.commType().value(), r.closeYm().value(),
                    r.calcAmount().toLong(), r.status().name());
        }
    }

    public record LimitSummary(long limitAmount, long accumPaid, long available,
                               LocalDate fyStart, LocalDate fyEnd) {
        static LimitSummary from(CommissionQueryService.LimitSummary l) {
            return new LimitSummary(l.limitAmount().toLong(), l.accumPaid().toLong(),
                    l.available().toLong(), l.fyStart(), l.fyEnd());
        }
    }

    public record DeferralSchedule(String dueYm, long amount, String status) {
        static DeferralSchedule from(ScheduleEntry e) {
            return new DeferralSchedule(e.dueYm().value(), e.amount().toLong(), e.status().name());
        }
    }
}
