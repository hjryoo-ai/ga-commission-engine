package ga.comm.rule.admin;

/**
 * 시책 승인 경합 — 같은 코드·개시일에 ACTIVE 시책이 이미 존재해 활성화가 거부됐다(§6.6).
 *
 * <p>앱 계층 겹침 검사(IncentiveApprovalService)를 동시 승인 2건이 모두 통과해도, DB의
 * function-based unique index(ux_incentive_active)가 최종 심판으로 두 번째 활성화를 차단한다.
 * 어댑터(OracleIncentiveAdminStore)가 스프링의 DuplicateKeyException을 이 예외로 번역하므로,
 * 웹 계층에서 raw 500이 아니라 <b>명시적 409(경합 거부)</b>로 매핑된다
 * ({@code IllegalStateException} 상속 → ApiExceptionHandler의 STATE_CONFLICT 매핑).
 *
 * <p>요율의 재시도 러너(OracleRateApprovalRunner)와 동형인 시책 재시도 러너는 후속 Phase다 —
 * 그때 이 예외 타입을 잡아 최신 상태 재조회·재판정으로 재시도한다(현재는 즉시 거부).
 */
public class IncentiveApprovalConflictException extends IllegalStateException {
    public IncentiveApprovalConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
