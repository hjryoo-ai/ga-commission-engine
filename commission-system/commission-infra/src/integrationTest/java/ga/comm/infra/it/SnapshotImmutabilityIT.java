package ga.comm.infra.it;

import ga.comm.disclosure.grade.DisclosureGradeService;
import ga.comm.disclosure.grade.GradeRequest;
import ga.comm.disclosure.grade.fixture.PolicyFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E8: 스냅샷·항목의 UPDATE·DELETE는 DB 트리거가 거부한다(V103, ORA-20301/20302) — 대상 행이 0건인 문장도 거부.
 * 어떤 경로(운영자 SQL 포함)로도 발급된 응답 원문과 항목이 바뀌지 않는다.
 */
class SnapshotImmutabilityIT {

    private static String snapshotId;
    private static final DisclosureSeed SEED = new DisclosureSeed(OracleTestSupport.dataSource());

    @BeforeAll
    static void issue() {
        OracleTestSupport.cleanAll();
        SEED.group().product("INS-A:PRD-1001", "0.8").product("INS-B:PRD-2044", "1.2").product("INS-C:PRD-3120", "1.0")
                .grading("GRADING-IMM", LocalDate.of(2026, 7, 1), null, "ACTIVE", PolicyFixtures.GRADING_5)
                .ranking("RANK-IMM", LocalDate.of(2026, 7, 1), null, "ACTIVE", PolicyFixtures.RANKING_STRICT);
        DisclosureGradeService service = DisclosureGradeOracleSupport.service(OracleTestSupport.persistence(),
                Clock.fixed(Instant.parse("2026-09-24T01:00:00Z"), ZoneId.of("Asia/Seoul")), "T1");
        snapshotId = service.issue(GradeRequest.of("T1", LocalDate.of(2026, 9, 24), DisclosureSeed.GROUP,
                List.of(new GradeRequest.Product("INS-A:PRD-1001", "INS-A"), new GradeRequest.Product("INS-Q:NONE", "INS-Q"))))
                .snapshotId();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE DISC_GRADE_SNAPSHOT SET response_canonical = '{}' WHERE snapshot_id = ?",
            "UPDATE DISC_GRADE_SNAPSHOT SET tenant_id = 'T9' WHERE snapshot_id = ?",
            "DELETE FROM DISC_GRADE_SNAPSHOT WHERE snapshot_id = ?",
            "DELETE FROM DISC_GRADE_SNAPSHOT WHERE snapshot_id = ? AND 1 = 0",
    })
    void 스냅샷_헤더_변경_거부(String sql) {
        assertThatThrownBy(() -> SEED.exec(sql, snapshotId)).hasMessageContaining("ORA-20301");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE DISC_GRADE_SNAPSHOT_ITEM SET ratio_to_avg = '9.99' WHERE snapshot_id = ?",
            "UPDATE DISC_GRADE_SNAPSHOT_ITEM SET grade_ordinal = 1 WHERE snapshot_id = ?",
            "DELETE FROM DISC_GRADE_SNAPSHOT_ITEM WHERE snapshot_id = ?",
    })
    void 스냅샷_항목_변경_거부(String sql) {
        assertThatThrownBy(() -> SEED.exec(sql, snapshotId)).hasMessageContaining("ORA-20302");
        assertThat(snapshotId).startsWith("GRD-20260924-");
    }
}
