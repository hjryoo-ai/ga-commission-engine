package ga.comm.disclosure.grade.snapshot;

import java.util.Optional;

/**
 * 스냅샷 영속 포트. 저장은 호출자의 트랜잭션 안에서 일어난다.
 */
public interface GradeSnapshotStore {

    /** 발급 번호 최솟값·최댓값(7자리 전용 대역, V13 SEQUENCE와 같은 값). */
    long FIRST_NUMBER = 1_000_000L;
    long LAST_NUMBER = 9_999_999L;

    /**
     * 스냅샷 번호(E3.1): 인스턴스 전체에서 하나인 단조 증가 번호. 날짜마다 다시 세지 않고 빈틈이 있을 수 있다.
     * 잠금·재시도 없이 동시 호출이 서로 다른 값을 받는다(Oracle SEQUENCE).
     */
    long nextNumber();

    void save(GradeSnapshot snapshot, String responseCanonical, String responseSha256);

    Optional<StoredSnapshot> find(String snapshotId);
}
