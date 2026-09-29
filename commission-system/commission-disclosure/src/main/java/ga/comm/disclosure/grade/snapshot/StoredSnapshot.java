package ga.comm.disclosure.grade.snapshot;

/** 저장소에서 읽은 스냅샷 — 재조회는 이 정규 문자열을 그대로 돌려준다(재직렬화 금지). */
public record StoredSnapshot(String snapshotId, String tenantId, String responseCanonical, String responseSha256) {
}
