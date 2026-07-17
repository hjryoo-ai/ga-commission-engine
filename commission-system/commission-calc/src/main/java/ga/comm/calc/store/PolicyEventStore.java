package ga.comm.calc.store;

import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.type.ProcessStatus;

import java.util.Optional;

/** POLICY_EVENT 저장 포트 — event_key 멱등 저장. */
public interface PolicyEventStore {

    /**
     * event_key 기준 멱등 저장.
     * 신규면 ID가 부여된 이벤트와 duplicate=false, 기존 키면 저장된 이벤트와 duplicate=true.
     */
    UpsertResult upsertByKey(PolicyEvent event);

    /** 저장된 이벤트 단건 조회 — 재계산 배치가 요청된 event_id의 원본 입력을 복원할 때 쓴다. */
    Optional<PolicyEvent> findById(long eventId);

    void markProcessed(long eventId);

    void markFailed(long eventId, String reason);

    ProcessStatus statusOf(long eventId);

    /** 미처리(PENDING/FAILED) 이벤트 수 — 마감 체크리스트 ①. */
    long unprocessedCount();

    record UpsertResult(PolicyEvent event, boolean duplicate, ProcessStatus status) {
    }
}
