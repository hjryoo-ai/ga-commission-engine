package ga.comm.disclosure.grade.snapshot;

/** 이 테넌트 인스턴스에 없는 snapshotId(404). */
public class SnapshotNotFoundException extends RuntimeException {

    public SnapshotNotFoundException(String snapshotId) {
        super("snapshot " + snapshotId + " does not exist in this tenant");
    }
}
