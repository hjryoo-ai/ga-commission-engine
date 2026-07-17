package ga.comm.api.web;

import ga.comm.rule.admin.RateApprovalService;
import ga.comm.rule.model.CommRateRule;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.LocalDate;

/**
 * 요율 승인 컨트롤러 (설계서 §10 Phase 12·16, §6.6) — {@link RateApprovalService} 위에 얇게.
 *
 * <p><b>실승인자 = 인증 주체(§6.6 완결, Phase 16)</b>: 승인자는 요청 본문이 아니라 인증된
 * principal에서 온다 — 위조 불가한 실명 승인. 승인 API는 Security에서 ADMIN 역할로 보호되므로
 * principal은 항상 존재한다. 방어적으로 빈 값·"system" principal은 거부한다(시드/테스트 대역과 혼동 차단).
 */
@RestController
@RequestMapping("/api/rates")
public class RateApprovalController {

    private final RateApprovalService approvalService;

    public RateApprovalController(RateApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @PostMapping("/{rateId}/approve")
    public RateApprovalResponse approve(@PathVariable long rateId, Principal principal) {
        String approvedBy = principal == null ? null : principal.getName();
        if (approvedBy == null || approvedBy.isBlank()) {
            throw new IllegalStateException("인증된 승인자가 없습니다 — 인증 없는 승인은 금지됩니다");
        }
        if (approvedBy.trim().equalsIgnoreCase("system")) {
            throw new IllegalArgumentException(
                    "운영 승인은 실제 승인자여야 합니다 — \"system\"은 시드/테스트 경로만 허용됩니다");
        }
        return RateApprovalResponse.from(approvalService.approve(rateId, approvedBy.trim()));
    }

    public record RateApprovalResponse(
            long rateId,
            String direction,
            String insurerCd,
            String productKey,
            String commType,
            Integer installmentNo,
            long versionNo,
            String status,
            LocalDate applyFrom,
            LocalDate applyTo
    ) {
        static RateApprovalResponse from(CommRateRule r) {
            return new RateApprovalResponse(
                    r.rateId(),
                    r.direction().name(),
                    r.insurerCd().value(),
                    r.productKey().value(),
                    r.commType().value(),
                    r.installmentNo(),
                    r.versionNo(),
                    r.status().name(),
                    r.period().applyFrom(),
                    r.period().applyTo());
        }
    }
}
