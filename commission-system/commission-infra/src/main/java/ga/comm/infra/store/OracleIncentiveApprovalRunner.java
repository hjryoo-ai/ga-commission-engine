package ga.comm.infra.store;

import ga.comm.rule.admin.IncentiveApprovalConflictException;
import ga.comm.rule.admin.IncentiveApprovalService;
import ga.comm.rule.model.IncentiveRule;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * 시책 승인 동시성 러너 (설계서 §6.6) — 요율 {@link OracleRateApprovalRunner}와 동형.
 *
 * <p><b>명세: 락 획득 후 재검증(lock-then-revalidate).</b> 같은 코드의 동시 승인 2건은 앱 계층 겹침
 * 검사를 둘 다 통과할 수 있고, 같은 개시일 충돌의 최종 심판은 {@code ux_incentive_active}(V102)다.
 * 인덱스 위반은 어댑터가 {@link IncentiveApprovalConflictException}으로 번역하며 — 이는 예외가 아니라
 * <b>정상 경합</b>이다: 트랜잭션을 롤백하고 새 트랜잭션에서 <b>커밋된 최신 상태를 다시 읽어</b> 겹침을
 * 재판정한다(트리밍/SUPERSEDE). 재판정은 {@code replace}가 대상 행을 {@code FOR UPDATE}로 잠근
 * 뒤 수행되므로 재시도가 최신 상태 위에서 결정된다. 경합이 {@value #MAX_ATTEMPTS}회를 넘으면 승인
 * 자체를 거부한다 — 침묵 수렴 금지.
 *
 * <p>정합성 자체(겹침 → 이중 지급)는 이 러너가 아니라 계산 시점 Ambiguous fail-fast(v1.1.9)가 보장한다.
 * 러너는 그 위에서 "409 대신 자동 수렴"을 제공하는 편의 계층이다. 터미널 상태 오류(DRAFT 아님 등)는
 * {@code IncentiveApprovalConflictException}이 아니므로 재시도하지 않고 즉시 전파된다.
 */
public class OracleIncentiveApprovalRunner {

    private static final int MAX_ATTEMPTS = 3;

    private final TransactionTemplate txTemplate;
    private final IncentiveApprovalService service;

    public OracleIncentiveApprovalRunner(TransactionTemplate txTemplate,
                                         IncentiveApprovalService service) {
        this.txTemplate = Objects.requireNonNull(txTemplate);
        this.service = Objects.requireNonNull(service);
    }

    public IncentiveRule approve(long incentiveId, String approvedBy) {
        IncentiveApprovalConflictException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return txTemplate.execute(status -> service.approve(incentiveId, approvedBy));
            } catch (IncentiveApprovalConflictException conflict) {
                // ux_incentive_active 위반 = 동시 승인 경합. 다음 루프가 새 트랜잭션에서 최신 커밋
                // 상태를 다시 읽고(lock-then-revalidate) 트리밍/SUPERSEDE를 재판정한다.
                lastConflict = conflict;
            }
        }
        throw new IllegalStateException(
                "시책 승인 경합이 " + MAX_ATTEMPTS + "회 반복되어 거부합니다: incentiveId=" + incentiveId,
                lastConflict);
    }
}
