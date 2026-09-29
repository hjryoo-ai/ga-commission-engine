package ga.comm.disclosure.grade.fixture;

import ga.comm.disclosure.grade.DisclosureGradeService;
import ga.comm.disclosure.grade.GradeRequest;
import ga.comm.disclosure.grade.measure.FySalesCommissionRateMeasure;
import ga.comm.disclosure.grade.measure.MeasureRegistry;
import ga.comm.disclosure.grade.policy.PolicyResolver;
import ga.comm.disclosure.grade.policy.PolicySource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

/**
 * 인메모리 시나리오 빌더: 상품군 1개(코드 체계 PG-V1) + 소속 상품·FY_COMM 요율 + 정책 픽스처 + 고정 시계(Asia/Seoul).
 * 외부 키 {@code INS-A:PRD-1001} → 엔진 키 (INS-A, PRD-1001).
 */
public final class GradeScenario {

    public static final String TENANT = "T1";
    public static final String SYSTEM = "PG-V1";
    public static final String GROUP = "PG-HEALTH-SIMPLE-NR";
    public static final LocalDate AS_OF = LocalDate.of(2026, 9, 23);
    public static final LocalDate DATA_FROM = LocalDate.of(2026, 1, 1);
    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 2026-09-23T10:15:30+09:00 */
    public static final Instant NOW = Instant.parse("2026-09-23T01:15:30Z");

    public final InMemoryDisclosurePolicyRepository policies = new InMemoryDisclosurePolicyRepository();
    public final InMemoryProductGroupDirectory groups = new InMemoryProductGroupDirectory().group(SYSTEM, GROUP, DATA_FROM);
    public final InMemorySalesRateLedger rates = new InMemorySalesRateLedger();
    public final InMemoryGradeSnapshotStore snapshots = new InMemoryGradeSnapshotStore();
    public Clock clock = Clock.fixed(NOW, SEOUL);
    public String instanceTenant = TENANT;

    public static GradeScenario standard(String gradingFixture, String rankingFixture) {
        return new GradeScenario().grading(gradingFixture).ranking(rankingFixture);
    }

    public GradeScenario grading(String fixture) {
        policies.addGrading(gradingId(fixture), LocalDate.of(2026, 7, 1), null, "ACTIVE", PolicyFixtures.read(fixture));
        return this;
    }

    public GradeScenario ranking(String fixture) {
        policies.addRanking(rankingId(fixture), LocalDate.of(2026, 7, 1), null, "ACTIVE", PolicyFixtures.read(fixture));
        return this;
    }

    public static String gradingId(String fixture) {
        return "GRADING-" + fixture.replace(".json", "").toUpperCase();
    }

    public static String rankingId(String fixture) {
        return "RANK-" + fixture.replace(".json", "").toUpperCase();
    }

    /** 소속 + FY_COMM 요율(회차 NULL, 2026-01-01부터). */
    public GradeScenario product(String extKey, String rate) {
        member(extKey);
        String[] parts = extKey.split(":");
        rates.fy(parts[0], parts[1], rate, DATA_FROM);
        return this;
    }

    /** 요율 없는 소속. */
    public GradeScenario member(String extKey) {
        String[] parts = extKey.split(":");
        groups.member(SYSTEM, GROUP, extKey, parts[0], parts[1], DATA_FROM);
        return this;
    }

    public DisclosureGradeService service() {
        return service(new PolicyResolver(policies));
    }

    public DisclosureGradeService service(PolicySource source) {
        return new DisclosureGradeService(source, groups,
                MeasureRegistry.of(List.of(new FySalesCommissionRateMeasure(rates))), snapshots, new DirectTransactions(),
                clock, instanceTenant);
    }

    public static GradeRequest request(String... extKeys) {
        return request(AS_OF, extKeys);
    }

    public static GradeRequest request(LocalDate asOf, String... extKeys) {
        return GradeRequest.of(TENANT, asOf, GROUP, Arrays.stream(extKeys)
                .map(k -> new GradeRequest.Product(k, k.substring(0, k.indexOf(':')))).toList());
    }
}
