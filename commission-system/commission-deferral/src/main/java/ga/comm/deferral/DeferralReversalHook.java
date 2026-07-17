package ga.comm.deferral;

import ga.comm.calc.revision.RevisionService;
import ga.comm.calc.store.CommCalcRecord;

import java.util.Objects;

/**
 * 취소분개 시 원본 계산에서 파생된 미도래(SCHEDULED) 분급 스케줄을 소멸시킨다.
 * rebook이 새 스케줄을 다시 생성하므로 재계산 후에도 "스케줄 합 = 이연 원금"이 유지된다.
 */
public class DeferralReversalHook implements RevisionService.ReversalHook {

    private final DeferralScheduleStore scheduleStore;

    public DeferralReversalHook(DeferralScheduleStore scheduleStore) {
        this.scheduleStore = Objects.requireNonNull(scheduleStore);
    }

    @Override
    public void onReversal(CommCalcRecord original, CommCalcRecord reversal) {
        for (ScheduleEntry entry : scheduleStore.findBySourceCalcId(original.calcId())) {
            if (entry.status() == ScheduleStatus.SCHEDULED) {
                scheduleStore.replace(entry.cancelled());
            }
        }
    }
}
