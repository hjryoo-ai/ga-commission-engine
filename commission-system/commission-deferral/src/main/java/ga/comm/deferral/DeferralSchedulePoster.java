package ga.comm.deferral;

import ga.comm.calc.CalcContext;
import ga.comm.calc.CalcResultListener;
import ga.comm.domain.id.AgentId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** COMM_CALC 저장 직후 분급 스케줄 생성 — source_calc_id로 근거를 잇는다. */
public class DeferralSchedulePoster implements CalcResultListener {

    private final DeferralScheduleStore scheduleStore;

    public DeferralSchedulePoster(DeferralScheduleStore scheduleStore) {
        this.scheduleStore = Objects.requireNonNull(scheduleStore);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onPersisted(CalcContext ctx, List<PersistedLine> lines) {
        List<DeferralSplitStep.PendingSchedule> pending =
                ctx.attachment(DeferralSplitStep.ATTACH_PENDING_SCHEDULES, List.class)
                        .map(l -> (List<DeferralSplitStep.PendingSchedule>) l)
                        .orElse(List.of());
        if (pending.isEmpty()) {
            return;
        }

        List<ScheduleEntry> entries = new ArrayList<>();
        for (DeferralSplitStep.PendingSchedule schedule : pending) {
            PersistedLine source = lines.stream()
                    .filter(p -> p.lineIndex() == schedule.lineIndex())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "분급 원본 라인이 저장되지 않았습니다: lineIndex=" + schedule.lineIndex()));
            entries.add(new ScheduleEntry(null, source.record().calcId(),
                    ctx.event().policyNo(), new AgentId(ctx.target().recipient().id()),
                    schedule.dueYm(), schedule.amount(),
                    ScheduleEntry.CONDITION_POLICY_INFORCE, ScheduleStatus.SCHEDULED,
                    schedule.curveId(), null));
        }
        scheduleStore.createAll(entries);
    }
}
