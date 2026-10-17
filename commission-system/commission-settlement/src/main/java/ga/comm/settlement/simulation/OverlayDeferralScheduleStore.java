package ga.comm.settlement.simulation;

import ga.comm.deferral.DeferralScheduleStore;
import ga.comm.deferral.ScheduleEntry;
import ga.comm.deferral.ScheduleStatus;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.time.CloseYm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 분급 스케줄 오버레이(E2 ②). 읽기는 원 저장소 조회 + 메모리 생성분, 생성·전이({@code replace})는 메모리에만. 원 저장소의 SCHEDULED가
 * 이 시뮬레이션에서 취소되면 {@link #cancelledStored()}에 남는다(policy-event 결과의 {@code cancelledDeferrals}).
 */
public final class OverlayDeferralScheduleStore implements DeferralScheduleStore {

    private final SimulationReads reads;
    private final List<ScheduleEntry> created = new ArrayList<>();
    private final Map<Long, ScheduleEntry> replaced = new LinkedHashMap<>();
    private long nextId = 0;

    public OverlayDeferralScheduleStore(SimulationReads reads) {
        this.reads = Objects.requireNonNull(reads);
    }

    /** 이 시뮬레이션이 만든 스케줄(생성 순, 현재 상태). */
    public List<ScheduleEntry> created() {
        return List.copyOf(created);
    }

    /** 원 저장소의 스케줄 가운데 이 시뮬레이션이 취소한 것(scheduleId 순). */
    public List<ScheduleEntry> cancelledStored() {
        return replaced.values().stream()
                .filter(e -> e.scheduleId() > 0 && e.status() == ScheduleStatus.CANCELLED)
                .sorted((a, b) -> Long.compare(a.scheduleId(), b.scheduleId()))
                .toList();
    }

    @Override
    public List<ScheduleEntry> createAll(List<ScheduleEntry> entries) {
        List<ScheduleEntry> out = new ArrayList<>();
        for (ScheduleEntry e : entries) {
            ScheduleEntry withId = e.withId(--nextId);
            created.add(withId);
            out.add(withId);
        }
        return out;
    }

    @Override
    public List<ScheduleEntry> findDue(CloseYm dueYm) {
        return merged(reads.schedulesDue().apply(dueYm), e -> e.dueYm().equals(dueYm)).stream()
                .filter(e -> e.status() == ScheduleStatus.SCHEDULED)
                .toList();
    }

    @Override
    public List<ScheduleEntry> findByPolicyAndAgent(PolicyNo policyNo, AgentId agentId) {
        return merged(reads.schedulesByPolicy().apply(policyNo, agentId),
                e -> e.policyNo().equals(policyNo) && e.agentId().equals(agentId));
    }

    @Override
    public List<ScheduleEntry> findBySourceCalcId(long sourceCalcId) {
        if (sourceCalcId < 0) {
            return created.stream().filter(e -> e.sourceCalcId() == sourceCalcId).toList();
        }
        return merged(reads.schedulesBySource().apply(sourceCalcId), e -> e.sourceCalcId() == sourceCalcId);
    }

    @Override
    public void replace(ScheduleEntry entry) {
        long id = entry.scheduleId();
        if (id < 0) {
            for (int i = 0; i < created.size(); i++) {
                if (created.get(i).scheduleId() == id) {
                    created.set(i, entry);
                    return;
                }
            }
            throw new IllegalStateException("존재하지 않는 scheduleId: " + id);
        }
        replaced.put(id, entry);
    }

    private List<ScheduleEntry> merged(List<ScheduleEntry> stored, Predicate<ScheduleEntry> mine) {
        List<ScheduleEntry> out = new ArrayList<>();
        for (ScheduleEntry e : stored) {
            out.add(replaced.getOrDefault(e.scheduleId(), e));
        }
        created.stream().filter(mine).forEach(out::add);
        return out;
    }
}
