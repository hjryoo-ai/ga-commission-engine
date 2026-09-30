package ga.comm.infra.it;

import ga.comm.disclosure.grade.DisclosureGradeService;
import ga.comm.disclosure.grade.GradeRequest;
import ga.comm.disclosure.grade.fixture.PolicyFixtures;
import ga.comm.infra.OraclePersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3.1 §3-4(Oracle Testcontainers): 스냅샷 채번은 Oracle SEQUENCE 하나다(일자 카운터 행·MERGE·재시도 없음).
 * 아직 한 건도 발급되지 않은 날에 같은 순간 50건을 동시에 발급해도 실패 0, ID 50개 전부 유일하고 형식 {@code GRD-yyyyMMdd-NNNNNNN}.
 */
class SnapshotNumberingConcurrencyIT {

    private static final String TENANT = "T1";
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 23);
    private static final int N = 50;

    private final OraclePersistence persistence = OracleTestSupport.persistence();
    private final DisclosureSeed seed = new DisclosureSeed(OracleTestSupport.dataSource());

    @BeforeEach
    void seed() {
        OracleTestSupport.cleanAll();
        seed.group()
                .product("INS-A:PRD-1001", "0.840000")
                .product("INS-B:PRD-2044", "1.370000")
                .product("INS-C:PRD-3120", "1.020000")
                .grading("GRADING-2026-07", LocalDate.of(2026, 7, 1), null, "ACTIVE", PolicyFixtures.GRADING_5)
                .ranking("RANK-2026-07", LocalDate.of(2026, 7, 1), null, "ACTIVE", PolicyFixtures.RANKING_SHARED);
    }

    @Test
    void 같은_순간_50건_발급은_실패_0_ID_유일() throws Exception {
        // 다른 테스트가 발급하지 않은 날짜(첫 발급일) — 일자 카운터 방식이면 첫 행 경합이 나는 조건
        Clock clock = Clock.fixed(Instant.parse("2031-03-15T01:00:00Z"), ZoneId.of("Asia/Seoul"));
        DisclosureGradeService service = DisclosureGradeOracleSupport.service(persistence, clock, TENANT);
        GradeRequest request = GradeRequest.of(TENANT, AS_OF, DisclosureSeed.GROUP, List.of(
                new GradeRequest.Product("INS-A:PRD-1001", "INS-A"), new GradeRequest.Product("INS-B:PRD-2044", "INS-B"),
                new GradeRequest.Product("INS-C:PRD-3120", "INS-C")));

        CountDownLatch ready = new CountDownLatch(N);
        CountDownLatch go = new CountDownLatch(1);
        List<String> ids = Collections.synchronizedList(new ArrayList<>());
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(N);
        try {
            for (int i = 0; i < N; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        ids.add(service.issue(request).snapshotId());
                    } catch (Throwable e) {
                        failures.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                });
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.MINUTES)).isTrue();
        }

        assertThat(failures).isEmpty();
        assertThat(ids).hasSize(N).doesNotHaveDuplicates().allMatch(id -> id.matches("GRD-20310315-\\d{7}"));
        assertThat(seed.query("SELECT COUNT(*) AS n FROM DISC_GRADE_SNAPSHOT WHERE snapshot_id LIKE 'GRD-20310315-%'")
                .getFirst().get("n")).isEqualTo(String.valueOf(N));
    }
}
