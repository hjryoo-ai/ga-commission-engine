package ga.comm.disclosure.grade.fixture;

import ga.comm.disclosure.grade.snapshot.GradeSnapshot;
import ga.comm.disclosure.grade.snapshot.GradeSnapshotStore;
import ga.comm.disclosure.grade.snapshot.StoredSnapshot;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryGradeSnapshotStore implements GradeSnapshotStore {

    private final Map<LocalDate, Integer> sequences = new HashMap<>();
    private final Map<String, StoredSnapshot> stored = new LinkedHashMap<>();
    private final Map<String, GradeSnapshot> snapshots = new LinkedHashMap<>();

    @Override
    public int nextSequence(LocalDate day) {
        return sequences.merge(day, 1, Integer::sum);
    }

    @Override
    public void save(GradeSnapshot snapshot, String responseCanonical, String responseSha256) {
        if (stored.putIfAbsent(snapshot.snapshotId(), new StoredSnapshot(snapshot.snapshotId(), snapshot.tenantId(),
                responseCanonical, responseSha256)) != null) {
            throw new IllegalStateException("snapshot is immutable: " + snapshot.snapshotId());
        }
        snapshots.put(snapshot.snapshotId(), snapshot);
    }

    @Override
    public Optional<StoredSnapshot> find(String snapshotId) {
        return Optional.ofNullable(stored.get(snapshotId));
    }

    public int count() {
        return stored.size();
    }

    public GradeSnapshot snapshot(String snapshotId) {
        return snapshots.get(snapshotId);
    }

    /** 테스트 전용: 저장된 정규 문자열 변조(무결성 검사 확인용). */
    public void tamper(String snapshotId, String canonical) {
        StoredSnapshot s = stored.get(snapshotId);
        stored.put(snapshotId, new StoredSnapshot(s.snapshotId(), s.tenantId(), canonical, s.responseSha256()));
    }
}
