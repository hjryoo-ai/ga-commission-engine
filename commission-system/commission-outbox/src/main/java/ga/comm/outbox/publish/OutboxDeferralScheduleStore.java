package ga.comm.outbox.publish;

import ga.comm.deferral.DeferralScheduleStore;
import ga.comm.deferral.DeferralTransitionReason;
import ga.comm.deferral.ScheduleEntry;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.time.CloseYm;

import java.util.List;
import java.util.Objects;

/**
 * 분급 스케줄의 두 사실(D-10). {@code createAll} → 행마다 {@code DeferralScheduled}(reason {@code CURVE} — E1 P1),
 * {@code replace(entry, reason)} → 상태가 바뀌었으면 {@code DeferralStatusChanged}. 이전 상태는 같은 트랜잭션에서 원본 계산의 스케줄로 읽는다.
 * 사유 없는 {@code replace(entry)}는 거부한다 — 업무 코드의 전이 지점은 전부 사유를 넘긴다(분급 모듈의 유일한 포트 변경, E1 승인 §2).
 */
public final class OutboxDeferralScheduleStore implements DeferralScheduleStore {

    public static final String SCHEDULED = "DeferralScheduled";
    public static final String STATUS_CHANGED = "DeferralStatusChanged";
    public static final String KIND = "DEFERRAL_SCHEDULE";

    private final DeferralScheduleStore delegate;
    private final OutboxPublisher publisher;

    public OutboxDeferralScheduleStore(DeferralScheduleStore delegate, OutboxPublisher publisher) {
        this.delegate = Objects.requireNonNull(delegate);
        this.publisher = Objects.requireNonNull(publisher);
    }

    @Override
    public List<ScheduleEntry> createAll(List<ScheduleEntry> entries) {
        List<ScheduleEntry> created = delegate.createAll(entries);
        for (ScheduleEntry e : created) {
            publisher.publish(SCHEDULED, KIND, Long.toString(e.scheduleId()), PortalPayloads.deferralScheduled(e));
        }
        return created;
    }

    @Override
    public List<ScheduleEntry> findDue(CloseYm dueYm) {
        return delegate.findDue(dueYm);
    }

    @Override
    public List<ScheduleEntry> findByPolicyAndAgent(PolicyNo policyNo, AgentId agentId) {
        return delegate.findByPolicyAndAgent(policyNo, agentId);
    }

    @Override
    public List<ScheduleEntry> findBySourceCalcId(long sourceCalcId) {
        return delegate.findBySourceCalcId(sourceCalcId);
    }

    @Override
    public void replace(ScheduleEntry entry) {
        throw new UnsupportedOperationException("분급 전이는 사유와 함께 — replace(entry, reason): scheduleId=" + entry.scheduleId());
    }

    @Override
    public void replace(ScheduleEntry entry, DeferralTransitionReason reason) {
        Objects.requireNonNull(reason, "reason");
        ScheduleEntry before = delegate.findBySourceCalcId(entry.sourceCalcId()).stream()
                .filter(e -> Objects.equals(e.scheduleId(), entry.scheduleId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("전이할 분급 스케줄이 없다: scheduleId=" + entry.scheduleId()));
        delegate.replace(entry, reason);
        if (before.status() != entry.status()) {
            publisher.publish(STATUS_CHANGED, KIND, Long.toString(entry.scheduleId()),
                    PortalPayloads.deferralStatusChanged(entry, before.status(), reason));
        }
    }
}
