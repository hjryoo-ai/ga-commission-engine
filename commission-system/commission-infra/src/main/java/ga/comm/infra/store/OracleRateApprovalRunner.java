package ga.comm.infra.store;

import ga.comm.rule.admin.RateApprovalService;
import ga.comm.rule.model.CommRateRule;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * 승인 동시성 러너 (설계서 §6.6 v1.1.2).
 *
 * <p>같은 키의 동시 승인 2건은 앱 계층 겹침 검사를 둘 다 통과할 수 있다. 최종 심판은
 * ux_comm_rate_active(V100)이며, 인덱스 위반은 예외가 아니라 <b>정상 경합</b>이다:
 * 트랜잭션을 롤백하고 새 트랜잭션에서 최신 상태를 재조회해 재검증(재시도)한다.
 * 재시도 중 분할 등으로 승인이 불가능해지면 {@link RateApprovalService}의 검증이
 * 명시적으로 거부한다. 경합이 한도를 넘으면 승인 자체를 거부한다 — 침묵 수렴 금지.
 */
public class OracleRateApprovalRunner {

    private static final int MAX_ATTEMPTS = 3;

    private final TransactionTemplate txTemplate;
    private final RateApprovalService service;

    public OracleRateApprovalRunner(TransactionTemplate txTemplate, RateApprovalService service) {
        this.txTemplate = Objects.requireNonNull(txTemplate);
        this.service = Objects.requireNonNull(service);
    }

    public CommRateRule approve(long rateId, String approvedBy) {
        DataIntegrityViolationException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return txTemplate.execute(status -> service.approve(rateId, approvedBy));
            } catch (DataIntegrityViolationException conflict) {
                // ux_comm_rate_active 위반 = 동시 승인 경합. 다음 루프가 새 트랜잭션에서
                // 최신 커밋 상태를 다시 읽고 트리밍/SUPERSEDE를 재판정한다.
                lastConflict = conflict;
            }
        }
        throw new IllegalStateException(
                "승인 경합이 " + MAX_ATTEMPTS + "회 반복되어 거부합니다: rateId=" + rateId, lastConflict);
    }
}
