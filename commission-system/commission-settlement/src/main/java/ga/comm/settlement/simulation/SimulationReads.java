package ga.comm.settlement.simulation;

import ga.comm.calc.store.CommCalcRecord;
import ga.comm.deferral.ScheduleEntry;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.time.CloseYm;
import ga.comm.limit.LimitLedger;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 시뮬레이션 오버레이의 원 저장소 입력 — <b>읽기 함수 참조 묶음</b>(E2 승인 Q1, ①의 {@code EstimateReads}와 같은 방식). 오버레이는 저장소 객체가
 * 아니라 조회 메서드 참조만 받으므로 원 저장소의 쓰기 메서드가 손에 없다 — 운영 발행 데코레이터({@code PublishingStores})를 받을 수 없다는 것이
 * <b>생성자 시그니처로</b> 보장된다(시험이 아니라 컴파일로).
 *
 * @param calcById            {@code CommCalcStore::findById}
 * @param calcsByEvent        {@code CommCalcStore::findByEventId}
 * @param calcsByPolicy       {@code CommCalcStore::findByPolicyAndRecipient}
 * @param calcsOfMonth        {@code CommCalcStore::findByCloseYm}
 * @param ledgerPeek          {@code LimitLedgerStore::peek} — <b>잠그지 않는</b> 읽기(Oracle {@code peekByKey}, C3). {@code find}를 넣으면 잠근다(주입 S7)
 * @param schedulesDue        {@code DeferralScheduleStore::findDue}
 * @param schedulesByPolicy   {@code DeferralScheduleStore::findByPolicyAndAgent}
 * @param schedulesBySource   {@code DeferralScheduleStore::findBySourceCalcId}
 * @param eventById           {@code PolicyEventStore::findById}
 * @param agentExists         엔진 설계사 마스터에 있는가(404 {@code AGENT_NOT_FOUND})
 */
public record SimulationReads(Function<Long, Optional<CommCalcRecord>> calcById,
                              Function<Long, List<CommCalcRecord>> calcsByEvent,
                              BiFunction<PolicyNo, String, List<CommCalcRecord>> calcsByPolicy,
                              Function<CloseYm, List<CommCalcRecord>> calcsOfMonth,
                              BiFunction<PolicyNo, AgentId, Optional<LimitLedger>> ledgerPeek,
                              Function<CloseYm, List<ScheduleEntry>> schedulesDue,
                              BiFunction<PolicyNo, AgentId, List<ScheduleEntry>> schedulesByPolicy,
                              Function<Long, List<ScheduleEntry>> schedulesBySource,
                              Function<Long, Optional<PolicyEvent>> eventById,
                              Predicate<String> agentExists) {

    public SimulationReads {
        Objects.requireNonNull(calcById);
        Objects.requireNonNull(calcsByEvent);
        Objects.requireNonNull(calcsByPolicy);
        Objects.requireNonNull(calcsOfMonth);
        Objects.requireNonNull(ledgerPeek);
        Objects.requireNonNull(schedulesDue);
        Objects.requireNonNull(schedulesByPolicy);
        Objects.requireNonNull(schedulesBySource);
        Objects.requireNonNull(eventById);
        Objects.requireNonNull(agentExists);
    }
}
