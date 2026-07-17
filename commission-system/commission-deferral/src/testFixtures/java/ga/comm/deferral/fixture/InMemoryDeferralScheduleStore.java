package ga.comm.deferral.fixture;

import ga.comm.deferral.DeferralScheduleStore;
import ga.comm.deferral.ScheduleEntry;
import ga.comm.deferral.ScheduleStatus;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.time.CloseYm;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 인메모리 분급 스케줄 저장소. */
public class InMemoryDeferralScheduleStore implements DeferralScheduleStore {

    private final List<ScheduleEntry> entries = new ArrayList<>();
    private final AtomicLong idSeq = new AtomicLong(0);

    @Override
    public synchronized List<ScheduleEntry> createAll(List<ScheduleEntry> newEntries) {
        List<ScheduleEntry> created = new ArrayList<>();
        for (ScheduleEntry entry : newEntries) {
            ScheduleEntry withId = entry.withId(idSeq.incrementAndGet());
            entries.add(withId);
            created.add(withId);
        }
        return created;
    }

    @Override
    public synchronized List<ScheduleEntry> findDue(CloseYm dueYm) {
        return entries.stream()
                .filter(e -> e.dueYm().equals(dueYm) && e.status() == ScheduleStatus.SCHEDULED)
                .toList();
    }

    @Override
    public synchronized List<ScheduleEntry> findByPolicyAndAgent(PolicyNo policyNo, AgentId agentId) {
        return entries.stream()
                .filter(e -> e.policyNo().equals(policyNo) && e.agentId().equals(agentId))
                .toList();
    }

    @Override
    public synchronized List<ScheduleEntry> findBySourceCalcId(long sourceCalcId) {
        return entries.stream().filter(e -> e.sourceCalcId() == sourceCalcId).toList();
    }

    @Override
    public synchronized void replace(ScheduleEntry entry) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).scheduleId().equals(entry.scheduleId())) {
                entries.set(i, entry);
                return;
            }
        }
        throw new IllegalStateException("존재하지 않는 scheduleId: " + entry.scheduleId());
    }

    public synchronized List<ScheduleEntry> all() {
        return List.copyOf(entries);
    }
}
