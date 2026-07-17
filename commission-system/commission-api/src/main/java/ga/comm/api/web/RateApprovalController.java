package ga.comm.api.web;

import ga.comm.rule.admin.RateApprovalService;
import ga.comm.rule.model.CommRateRule;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 요율 승인 컨트롤러 (설계서 §10 Phase 12, §6.6) — {@link RateApprovalService} 위에 얇게.
 *
 * <p><b>실승인자 필수(§6.6)</b>: 운영 승인 경로는 실제 승인자 식별자를 필수로 전달한다.
 * 빈 값·"system"은 거부한다("system" 기록은 시드/테스트 경로만 허용). 인증 미도입 상태의
 * 임시 전달 방식이며, 인증 연동 시 요청 본문 approvedBy는 인증 주체(principal)로 대체된다.
 */
@RestController
@RequestMapping("/api/rates")
public class RateApprovalController {

    private final RateApprovalService approvalService;

    public RateApprovalController(RateApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @PostMapping("/{rateId}/approve")
    public RateApprovalResponse approve(@PathVariable long rateId,
                                        @RequestBody ApproveRequest request) {
        String approvedBy = request == null ? null : request.approvedBy();
        if (approvedBy == null || approvedBy.isBlank()) {
            throw new IllegalArgumentException("승인자(approvedBy)는 필수입니다");
        }
        if (approvedBy.trim().equalsIgnoreCase("system")) {
            throw new IllegalArgumentException(
                    "운영 승인은 실제 승인자여야 합니다 — \"system\"은 시드/테스트 경로만 허용됩니다");
        }
        return RateApprovalResponse.from(approvalService.approve(rateId, approvedBy.trim()));
    }

    public record ApproveRequest(String approvedBy) {
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
