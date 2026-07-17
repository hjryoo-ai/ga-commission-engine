package ga.comm.infra.store;

import ga.comm.deferral.DeferralScheduleStore;
import ga.comm.deferral.ScheduleEntry;
import ga.comm.deferral.ScheduleStatus;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.infra.mapper.DeferralScheduleMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** DEFERRAL_SCHEDULE Oracle 어댑터 — 갱신은 상태 전이 컬럼만 (금액·귀속은 불변). */
public class OracleDeferralScheduleStore implements DeferralScheduleStore {

    private final DeferralScheduleMapper mapper;

    public OracleDeferralScheduleStore(DeferralScheduleMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public List<ScheduleEntry> createAll(List<ScheduleEntry> entries) {
        List<ScheduleEntry> created = new ArrayList<>(entries.size());
        for (ScheduleEntry entry : entries) {
            DeferralScheduleMapper.Row row = toRow(entry);
            mapper.insert(row);
            created.add(entry.withId(row.scheduleId));
        }
        return created;
    }

    @Override
    public List<ScheduleEntry> findDue(CloseYm dueYm) {
        return mapper.findDue(dueYm.value()).stream().map(OracleDeferralScheduleStore::toDomain).toList();
    }

    @Override
    public List<ScheduleEntry> findByPolicyAndAgent(PolicyNo policyNo, AgentId agentId) {
        return mapper.findByPolicyAndAgent(policyNo.value(), agentId.value()).stream()
                .map(OracleDeferralScheduleStore::toDomain).toList();
    }

    @Override
    public List<ScheduleEntry> findBySourceCalcId(long sourceCalcId) {
        return mapper.findBySourceCalcId(sourceCalcId).stream()
                .map(OracleDeferralScheduleStore::toDomain).toList();
    }

    @Override
    public void replace(ScheduleEntry entry) {
        int updated = mapper.updateTransition(entry.scheduleId(), entry.status().name(),
                entry.releasedCalcId());
        if (updated == 0) {
            throw new IllegalStateException("존재하지 않는 scheduleId: " + entry.scheduleId());
        }
    }

    private static DeferralScheduleMapper.Row toRow(ScheduleEntry entry) {
        DeferralScheduleMapper.Row row = new DeferralScheduleMapper.Row();
        row.scheduleId = entry.scheduleId();
        row.sourceCalcId = entry.sourceCalcId();
        row.policyNo = entry.policyNo().value();
        row.agentId = entry.agentId().value();
        row.dueYm = entry.dueYm().value();
        row.amount = entry.amount().toLong();
        row.payCondition = entry.payCondition();
        row.status = entry.status().name();
        row.curveVersionId = entry.curveVersionId();
        row.releasedCalcId = entry.releasedCalcId();
        return row;
    }

    private static ScheduleEntry toDomain(DeferralScheduleMapper.Row row) {
        return new ScheduleEntry(row.scheduleId, row.sourceCalcId, new PolicyNo(row.policyNo),
                new AgentId(row.agentId), CloseYm.of(row.dueYm), Money.won(row.amount),
                row.payCondition, ScheduleStatus.valueOf(row.status), row.curveVersionId,
                row.releasedCalcId);
    }
}
