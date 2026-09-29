package ga.comm.disclosure.grade.snapshot;

/** 저장된 정규 응답의 SHA-256이 저장된 해시와 다름 — 반환하지 않는다(500, SNAPSHOT_INTEGRITY). */
public class SnapshotIntegrityException extends RuntimeException {

    public SnapshotIntegrityException(String snapshotId) {
        super("snapshot " + snapshotId + " failed its SHA-256 check");
    }
}
