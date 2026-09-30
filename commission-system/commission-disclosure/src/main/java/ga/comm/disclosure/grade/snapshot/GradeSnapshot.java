package ga.comm.disclosure.grade.snapshot;

import ga.comm.disclosure.grade.GradeResult;
import ga.comm.disclosure.grade.policy.TieBreak;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 발급된 스냅샷(= 계약 CommissionGradesResponse 전체 + 저장용 헤더). 불변이다(DB 트리거가 UPDATE·DELETE 거부). */
public record GradeSnapshot(String snapshotId, String tenantId, LocalDate asOfDate, String productGroupCode,
                            String gradingPolicyVersionId, String rankingPolicyVersionId, TieBreak tieBreak,
                            Basis basis, List<GradeResult> results, OffsetDateTime generatedAt) {

    public GradeSnapshot {
        results = List.copyOf(results);
    }

    public record Basis(String groupAvgSource, String period, int groupPopulation) {
    }
}
