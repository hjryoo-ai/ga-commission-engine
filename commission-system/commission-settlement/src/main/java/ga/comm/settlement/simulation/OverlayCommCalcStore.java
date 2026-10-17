package ga.comm.settlement.simulation;

import ga.comm.domain.type.CalcStatus;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.time.CloseYm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 계산 레코드 오버레이(E2 ② — 시뮬레이션 1회 수명). 읽기는 원 저장소의 조회 함수 + 메모리 삽입분, 삽입·상태 전이는 <b>메모리에만</b>.
 * 메모리 레코드의 ID는 음수(원 저장소의 IDENTITY와 겹치지 않는다 — 원 저장소의 시퀀스를 소비하지 않는다).
 */
public final class OverlayCommCalcStore implements CommCalcStore {

    private final SimulationReads reads;
    private final List<CommCalcRecord> inserted = new ArrayList<>();
    private final Map<Long, CalcStatus> statusOverrides = new HashMap<>();
    private long nextId = 0;

    public OverlayCommCalcStore(SimulationReads reads) {
        this.reads = Objects.requireNonNull(reads);
    }

    /** 이 시뮬레이션이 만든 레코드(삽입 순). */
    public List<CommCalcRecord> inserted() {
        return List.copyOf(inserted);
    }

    @Override
    public CommCalcRecord insert(CommCalcRecord record) {
        CommCalcRecord withId = record.withCalcId(--nextId);
        inserted.add(withId);
        return withId;
    }

    @Override
    public Optional<CommCalcRecord> findById(long calcId) {
        if (calcId < 0) {
            return inserted.stream().filter(r -> r.calcId() == calcId).findFirst();
        }
        return reads.calcById().apply(calcId).map(this::overridden);
    }

    @Override
    public List<CommCalcRecord> findByEventId(long eventId) {
        if (eventId < 0) {
            return inserted.stream().filter(r -> r.eventId() == eventId).toList();
        }
        return merged(reads.calcsByEvent().apply(eventId), r -> r.eventId() == eventId);
    }

    @Override
    public List<CommCalcRecord> findByPolicyAndRecipient(PolicyNo policyNo, String recipientId) {
        return merged(reads.calcsByPolicy().apply(policyNo, recipientId),
                r -> r.policyNo().equals(policyNo) && r.recipientId().equals(recipientId));
    }

    @Override
    public List<CommCalcRecord> findByCloseYm(CloseYm closeYm) {
        return merged(reads.calcsOfMonth().apply(closeYm), r -> r.closeYm().equals(closeYm));
    }

    @Override
    public CommCalcRecord transition(long calcId, CalcStatus to) {
        for (int i = 0; i < inserted.size(); i++) {
            if (inserted.get(i).calcId() == calcId) {
                CommCalcRecord moved = inserted.get(i).withStatus(to);
                inserted.set(i, moved);
                return moved;
            }
        }
        CommCalcRecord original = reads.calcById().apply(calcId)
                .orElseThrow(() -> new IllegalStateException("존재하지 않는 calcId: " + calcId));
        statusOverrides.put(calcId, to);
        return original.withStatus(to);
    }

    private List<CommCalcRecord> merged(List<CommCalcRecord> stored, java.util.function.Predicate<CommCalcRecord> mine) {
        List<CommCalcRecord> out = new ArrayList<>();
        for (CommCalcRecord r : stored) {
            out.add(overridden(r));
        }
        inserted.stream().filter(mine).forEach(out::add);
        return out;
    }

    private CommCalcRecord overridden(CommCalcRecord r) {
        CalcStatus to = statusOverrides.get(r.calcId());
        return to == null ? r : r.withStatus(to);
    }
}
