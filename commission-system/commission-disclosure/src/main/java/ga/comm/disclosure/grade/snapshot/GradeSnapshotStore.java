package ga.comm.disclosure.grade.snapshot;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 스냅샷 영속 포트. 모든 호출은 호출자의 트랜잭션 안에서 일어난다(채번 잠금 + 저장이 한 단위).
 */
public interface GradeSnapshotStore {

    /** 일자별 채번(1부터). 같은 날의 동시 요청은 행 잠금으로 직렬화된다. */
    int nextSequence(LocalDate day);

    void save(GradeSnapshot snapshot, String responseCanonical, String responseSha256);

    Optional<StoredSnapshot> find(String snapshotId);
}
