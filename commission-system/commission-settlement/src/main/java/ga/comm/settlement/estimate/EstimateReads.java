package ga.comm.settlement.estimate;

import ga.comm.calc.store.CommCalcRecord;
import ga.comm.clawback.ClawbackReceivable;
import ga.comm.deferral.PolicyStatusProvider;
import ga.comm.deferral.ScheduleEntry;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.time.CloseYm;
import ga.comm.settlement.AdjustmentService;
import ga.comm.settlement.SettleCloseStore;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * 잠정 명세 산출기의 입력 — <b>읽기 함수 참조 묶음</b>(E2 승인 Q1). 저장소 객체가 아니라 조회 메서드 참조만 받으므로 산출기 손에 쓰기 메서드가
 * 없다(컴파일로 보장 — 시험으로가 아니다). 조회는 전부 잠금 없는 SELECT다(Oracle 매퍼에서 {@code FOR UPDATE} 없음 — C3).
 *
 * @param recordsOfMonth 그 달 귀속 레코드 전부(상태 무관) — {@code CommCalcStore::findByCloseYm}
 * @param calcById       레코드 하나 — 분급 원본·정정 대상({@code CommCalcStore::findById})
 * @param dueOf          그 달 도래분(SCHEDULED) — {@code DeferralScheduleStore::findDue}
 * @param policyStatus   유지 판정(도래 처리와 같은 판정기 — P1)
 * @param offsettable    설계사의 상계 가능 채권(오래된 것부터) — {@code ClawbackReceivableStore::findOffsettable}
 * @param adjustmentsOf  그 달 귀속 정정 — {@code AdjustmentStore::findByCloseYm}
 * @param closeStateOf   마감 상태 — {@code SettleCloseStore::stateOf}
 */
public record EstimateReads(Function<CloseYm, List<CommCalcRecord>> recordsOfMonth,
                            Function<Long, Optional<CommCalcRecord>> calcById,
                            Function<CloseYm, List<ScheduleEntry>> dueOf,
                            PolicyStatusProvider policyStatus,
                            Function<AgentId, List<ClawbackReceivable>> offsettable,
                            Function<CloseYm, List<AdjustmentService.Adjustment>> adjustmentsOf,
                            Function<CloseYm, SettleCloseStore.CloseState> closeStateOf) {

    public EstimateReads {
        Objects.requireNonNull(recordsOfMonth);
        Objects.requireNonNull(calcById);
        Objects.requireNonNull(dueOf);
        Objects.requireNonNull(policyStatus);
        Objects.requireNonNull(offsettable);
        Objects.requireNonNull(adjustmentsOf);
        Objects.requireNonNull(closeStateOf);
    }
}
