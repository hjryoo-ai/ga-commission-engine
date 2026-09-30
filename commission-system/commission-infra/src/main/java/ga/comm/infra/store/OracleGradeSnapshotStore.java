package ga.comm.infra.store;

import com.fasterxml.jackson.databind.node.ObjectNode;
import ga.comm.disclosure.grade.GradeResult;
import ga.comm.disclosure.grade.snapshot.GradeSnapshot;
import ga.comm.disclosure.grade.snapshot.GradeSnapshotStore;
import ga.comm.disclosure.grade.snapshot.SnapshotJson;
import ga.comm.disclosure.grade.snapshot.StoredSnapshot;
import ga.comm.infra.mapper.DisclosureGradeMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 스냅샷 Oracle 어댑터(Phase E3). 채번은 일자 행을 {@code SELECT … FOR UPDATE}로 잠가 직렬화한다(첫 발급 날의 동시 INSERT 충돌은
 * PK 위반 → 호출 트랜잭션 실패로 드러나며 재시도는 호출자 몫). 저장은 INSERT뿐이며 DB 트리거(V103)가 UPDATE·DELETE를 거부한다.
 */
public class OracleGradeSnapshotStore implements GradeSnapshotStore {

    private final DisclosureGradeMapper mapper;

    public OracleGradeSnapshotStore(DisclosureGradeMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public int nextSequence(LocalDate day) {
        Integer last = mapper.lockSequence(day);
        if (last == null) {
            mapper.insertSequence(day);
            return 1;
        }
        mapper.incrementSequence(day);
        return last + 1;
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
