package ga.comm.infra.store;

import com.fasterxml.jackson.databind.node.ObjectNode;
import ga.comm.disclosure.grade.GradeResult;
import ga.comm.disclosure.grade.snapshot.GradeSnapshot;
import ga.comm.disclosure.grade.snapshot.GradeSnapshotStore;
import ga.comm.disclosure.grade.snapshot.SnapshotJson;
import ga.comm.disclosure.grade.snapshot.StoredSnapshot;
import ga.comm.infra.mapper.DisclosureGradeMapper;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 스냅샷 Oracle 어댑터(Phase E3). 채번은 SEQUENCE {@code DISC_GRADE_SNAPSHOT_NO}(V13, E3.1) — 잠금·경합·재시도가 없다.
 * 저장은 INSERT뿐이며 DB 트리거(V103)가 UPDATE·DELETE를 거부한다.
 */
public class OracleGradeSnapshotStore implements GradeSnapshotStore {

    private final DisclosureGradeMapper mapper;

    public OracleGradeSnapshotStore(DisclosureGradeMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public long nextNumber() {
        return mapper.nextSnapshotNumber();
    }

    @Override
    public void save(GradeSnapshot s, String responseCanonical, String responseSha256) {
        ObjectNode basis = SnapshotJson.mapper().createObjectNode();
        basis.put("groupAvgSource", s.basis().groupAvgSource());
        basis.put("period", s.basis().period());
        basis.put("groupPopulation", s.basis().groupPopulation());
        mapper.insertSnapshot(s.snapshotId(), s.tenantId(), s.asOfDate(), s.productGroupCode(), s.gradingPolicyVersionId(),
                s.rankingPolicyVersionId(), s.tieBreak().name(), basis.toString(), s.generatedAt(), responseCanonical, responseSha256);
        List<GradeResult> results = s.results();
        for (int i = 0; i < results.size(); i++) {
            mapper.insertItem(s.snapshotId(), item(results.get(i), i + 1));
        }
    }

    private static DisclosureGradeMapper.SnapshotItemRow item(GradeResult r, int order) {
        DisclosureGradeMapper.SnapshotItemRow row = new DisclosureGradeMapper.SnapshotItemRow();
        row.productKey = r.productKey();
        row.itemOrder = order;
        switch (r) {
            case GradeResult.Ok ok -> {
                row.status = "OK";
                row.ratioToAvg = ok.ratioToAvg();
                row.grade = ok.grade();
                row.gradeLabel = ok.gradeLabel();
                row.gradeOrdinal = ok.gradeOrdinal();
                row.rankInSet = ok.rankInSet();
                row.tie = ok.tie() ? 1 : 0;
            }
            case GradeResult.Unavailable u -> {
                row.status = "UNAVAILABLE";
                row.reason = u.reason();
            }
        }
        return row;
    }

    @Override
    public Optional<StoredSnapshot> find(String snapshotId) {
        DisclosureGradeMapper.SnapshotRow row = mapper.findSnapshot(snapshotId);
        return row == null ? Optional.empty()
                : Optional.of(new StoredSnapshot(row.snapshotId, row.tenantId, row.responseCanonical, row.responseSha256));
    }
}
