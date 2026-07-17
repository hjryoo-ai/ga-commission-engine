package ga.comm.calc.store;

import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;

import java.util.List;
import java.util.Optional;

/**
 * COMM_CALC 저장 포트 — 불변 원장 규약을 인터페이스 수준에서 강제한다:
 * insert와 상태 전이만 존재하고, 임의 UPDATE는 없다.
 */
public interface CommCalcStore {

    /** 저장 후 calcId가 부여된 레코드를 반환한다. */
    CommCalcRecord insert(CommCalcRecord record);

    Optional<CommCalcRecord> findById(long calcId);

    List<CommCalcRecord> findByEventId(long eventId);

    List<CommCalcRecord> findByPolicyAndRecipient(PolicyNo policyNo, String recipientId);

    List<CommCalcRecord> findByCloseYm(CloseYm closeYm);

    /**
     * 상태 전이 — {@link CalcStatus#canTransitionTo(CalcStatus)} 위반이면 예외.
     * 마감(CLOSED)된 귀속월 레코드의 전이 차단은 마감 모듈이 이 포트를 감싸서 강제한다.
     */
    CommCalcRecord transition(long calcId, CalcStatus to);
}
