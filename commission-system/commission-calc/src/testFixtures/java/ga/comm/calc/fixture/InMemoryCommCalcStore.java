package ga.comm.calc.fixture;

import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** 인메모리 COMM_CALC 저장소 — 불변 원장 규약(insert + 상태 전이만)을 그대로 강제한다. */
public class InMemoryCommCalcStore implements CommCalcStore {

    private final List<CommCalcRecord> records = new ArrayList<>();
    private final AtomicLong idSeq = new AtomicLong(0);

    @Override
    public synchronized CommCalcRecord insert(CommCalcRecord record) {
        if (record.calcId() != null) {
            throw new IllegalArgumentException("이미 calcId가 부여된 레코드입니다: " + record.calcId());
        }
        CommCalcRecord withId = record.withCalcId(idSeq.incrementAndGet());
        records.add(withId);
        return withId;
    }

    @Override
    public synchronized Optional<CommCalcRecord> findById(long calcId) {
        return records.stream().filter(r -> r.calcId() == calcId).findFirst();
    }

    @Override
    public synchronized List<CommCalcRecord> findByEventId(long eventId) {
        return records.stream().filter(r -> r.eventId() == eventId).toList();
    }

    @Override
    public synchronized List<CommCalcRecord> findByPolicyAndRecipient(PolicyNo policyNo, String recipientId) {
        return records.stream()
                .filter(r -> r.policyNo().equals(policyNo) && r.recipientId().equals(recipientId))
                .toList();
    }

    @Override
    public synchronized List<CommCalcRecord> findByCloseYm(CloseYm closeYm) {
        return records.stream().filter(r -> r.closeYm().equals(closeYm)).toList();
    }

    @Override
    public synchronized CommCalcRecord transition(long calcId, CalcStatus to) {
        for (int i = 0; i < records.size(); i++) {
            CommCalcRecord r = records.get(i);
            if (r.calcId() == calcId) {
                if (!r.status().canTransitionTo(to)) {
                    throw new IllegalStateException(
                            "허용되지 않는 상태 전이: " + r.status() + " → " + to + " (calcId=" + calcId + ")");
                }
                CommCalcRecord updated = r.withStatus(to);
                records.set(i, updated);
                return updated;
            }
        }
        throw new IllegalArgumentException("존재하지 않는 calcId: " + calcId);
    }

    public synchronized List<CommCalcRecord> all() {
        return List.copyOf(records);
    }
}
