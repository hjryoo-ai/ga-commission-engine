package ga.comm.disclosure.grade;

import com.fasterxml.jackson.databind.JsonNode;
import ga.comm.disclosure.grade.fixture.GradeScenario;
import ga.comm.disclosure.grade.fixture.PolicyFixtures;
import ga.comm.disclosure.grade.policy.GradeBand;
import ga.comm.disclosure.grade.policy.GradingPolicySpec;
import ga.comm.disclosure.grade.policy.InvalidPolicyException;
import ga.comm.disclosure.grade.policy.PolicyLoader;
import ga.comm.disclosure.grade.policy.PolicySource;
import ga.comm.disclosure.grade.policy.PolicyVersion;
import ga.comm.disclosure.grade.policy.RankingPolicySpec;
import ga.comm.disclosure.grade.snapshot.SnapshotIntegrityException;
import ga.comm.disclosure.grade.snapshot.SnapshotJson;
import ga.comm.disclosure.grade.snapshot.SnapshotNotFoundException;
import ga.comm.rule.AmbiguousRuleException;
import ga.comm.rule.RuleNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 서비스 경로 전반: 요청 판정·오류 분류·채번·재조회. */
class DisclosureGradeServiceTest {

    private static GradeScenario three() {
        return GradeScenario.standard(PolicyFixtures.GRADING_5, PolicyFixtures.RANKING_SHARED)
                .product("INS-A:PRD-1001", "0.840000")
                .product("INS-B:PRD-2044", "1.370000")
                .product("INS-C:PRD-3120", "1.020000");
    }

    private static JsonNode json(String body) throws Exception {
        return SnapshotJson.mapper().readTree(body);
    }

    @Test
    void 정상_발급_헤더와_basis() throws Exception {
        GradeScenario s = three();
        DisclosureGradeService.Issued issued = s.service().issue(GradeScenario.request("INS-A:PRD-1001", "INS-B:PRD-2044", "INS-C:PRD-3120"));
        JsonNode r = json(issued.responseCanonical());
        assertThat(issued.snapshotId()).isEqualTo("GRD-20260923-000001");
        assertThat(r.get("snapshotId").asText()).isEqualTo(issued.snapshotId());
        assertThat(r.get("tieBreak").asText()).isEqualTo("SHARED_RANK");
        assertThat(r.get("basis").toString()).isEqualTo("{\"groupAvgSource\":\"ENGINE_LEDGER\",\"groupPopulation\":3,\"period\":\"2026Q2\"}");
        assertThat(r.get("generatedAt").asText()).isEqualTo("2026-09-23T10:15:30+09:00");
        // 순위 = 측정값 오름차순(수수료가 낮을수록 1순위)
        assertThat(r.get("results").findValuesAsText("productKey")).containsExactly("INS-A:PRD-1001", "INS-C:PRD-3120", "INS-B:PRD-2044");
        // 정규 JSON: 공백 없음, 키 정렬
        assertThat(issued.responseCanonical()).doesNotContain(" :").startsWith("{\"basis\":");
        assertThat(s.service().issue(GradeScenario.request("INS-A:PRD-1001")).snapshotId()).isEqualTo("GRD-20260923-000002");
    }

    @Test
    void 테넌트_불일치는_403_계열() {
        GradeScenario s = three();
        s.instanceTenant = "T2";
        assertThatThrownBy(() -> s.service().issue(GradeScenario.request("INS-A:PRD-1001")))
                .isInstanceOf(TenantMismatchException.class);
        assertThat(s.snapshots.count()).isZero();
    }

    @Test
    void 미래_기준일은_정책_파라미터로_거부() {
        GradeScenario s = three();
        assertThatThrownBy(() -> s.service().issue(GradeScenario.request(GradeScenario.AS_OF.plusDays(1), "INS-A:PRD-1001")))
                .isInstanceOfSatisfying(GradeRequestException.class, e -> assertThat(e.code()).isEqualTo("AS_OF_IN_FUTURE"));
        assertThat(s.service().issue(GradeScenario.request(GradeScenario.AS_OF, "INS-A:PRD-1001")).snapshotId()).isNotBlank();
    }

    @Test
    void 코드_체계에_없는_상품군은_400() {
        GradeScenario s = three();
        GradeRequest request = GradeRequest.of("T1", GradeScenario.AS_OF, "PG-UNKNOWN",
                List.of(new GradeRequest.Product("INS-A:PRD-1001", "INS-A")));
        assertThatThrownBy(() -> s.service().issue(request))
                .isInstanceOfSatisfying(GradeRequestException.class, e -> assertThat(e.code()).isEqualTo("UNKNOWN_PRODUCT_GROUP"));
    }

    @Test
    void 정책_0건과_2건() {
        GradeScenario none = new GradeScenario().ranking(PolicyFixtures.RANKING_SHARED).product("INS-A:PRD-1001", "1.0");
        assertThatThrownBy(() -> none.service().issue(GradeScenario.request("INS-A:PRD-1001")))
                .isInstanceOf(RuleNotFoundException.class);
        GradeScenario two = three();
        two.policies.addGrading("GRADING-OTHER", LocalDate.of(2026, 9, 1), null, "ACTIVE", PolicyFixtures.read(PolicyFixtures.GRADING_4));
        assertThatThrownBy(() -> two.service().issue(GradeScenario.request("INS-A:PRD-1001")))
                .isInstanceOf(AmbiguousRuleException.class);
    }

    @Test
    void 산출_불가_원인별_사유_코드는_정책_데이터에서() throws Exception {
        GradeScenario s = three().member("INS-X:NO-RATE");
        s.groups.member(GradeScenario.SYSTEM, GradeScenario.GROUP, "INS-Y:OLD", "INS-Y", "OLD", GradeScenario.DATA_FROM);
        s.rates.add("INS-Y", "OLD", "FY_COMM", null, "0.9", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        JsonNode r = json(s.service().issue(GradeScenario.request(
                "INS-Z:NOT-MEMBER", "INS-X:NO-RATE", "INS-Y:OLD", "INS-A:PRD-1001")).responseCanonical());
        JsonNode results = r.get("results");
        assertThat(results.get(0).get("productKey").asText()).isEqualTo("INS-A:PRD-1001");
        assertThat(results.get(1).toString()).isEqualTo("{\"productKey\":\"INS-Z:NOT-MEMBER\",\"reason\":\"NOT_IN_GROUP\",\"status\":\"UNAVAILABLE\"}");
        assertThat(results.get(2).get("reason").asText()).isEqualTo("NO_RATE_DATA");
        assertThat(results.get(3).get("reason").asText()).isEqualTo("OUTSIDE_PERIOD");
        // 모집단 = 측정값이 있는 소속 3개(요율 없는 2개 제외)
        assertThat(r.get("basis").get("groupPopulation").asInt()).isEqualTo(3);
    }

    @Test
    void 모집단이_최소_크기_미만이면_상품군_전체_산출불가() throws Exception {
        GradeScenario s = GradeScenario.standard(PolicyFixtures.GRADING_5, PolicyFixtures.RANKING_SHARED)
                .product("INS-A:PRD-1001", "0.8").product("INS-B:PRD-2044", "1.2");
        JsonNode r = json(s.service().issue(GradeScenario.request("INS-A:PRD-1001", "INS-B:PRD-2044", "INS-Q:NONE")).responseCanonical());
        assertThat(r.get("results").findValuesAsText("reason")).containsExactly("INSUFFICIENT_POPULATION", "INSUFFICIENT_POPULATION",
                "INSUFFICIENT_POPULATION");
        assertThat(r.get("basis").get("groupPopulation").asInt()).isEqualTo(2);
    }

    @Test
    void 측정값_합이_0이면_모집단_부족으로_본다() throws Exception {
        GradeScenario s = GradeScenario.standard(PolicyFixtures.GRADING_5, PolicyFixtures.RANKING_SHARED)
                .product("INS-A:PRD-1001", "0").product("INS-B:PRD-2044", "0").product("INS-C:PRD-3120", "0");
        JsonNode r = json(s.service().issue(GradeScenario.request("INS-A:PRD-1001")).responseCanonical());
        assertThat(r.get("results").get(0).get("reason").asText()).isEqualTo("INSUFFICIENT_POPULATION");
    }

    @Test
    void 자기_검증_실패는_스냅샷을_만들지_않는다() {
        GradeScenario s = three();
        GradingPolicySpec valid = PolicyLoader.grading(PolicyFixtures.read(PolicyFixtures.GRADING_5));
        // 로드 검증을 우회해 ordinal을 비율과 반대로 매긴 모순 정책(DB 데이터 오염을 흉내)
        List<GradeBand> inverted = valid.grades().stream()
                .map(b -> new GradeBand(b.code(), b.label(), 6 - b.ordinal(), b.min(), b.max())).toList();
        GradingPolicySpec contradictory = new GradingPolicySpec(valid.measureKey(), valid.measureParams(), valid.groupCodeSystem(),
                valid.populationScope(), valid.minPopulation(), valid.periodKind(), valid.asOfRule(), valid.asOfFutureDaysAllowed(),
                valid.ratioScale(), valid.ratioRounding(), inverted, valid.unavailableReasons());
        RankingPolicySpec ranking = PolicyLoader.ranking(PolicyFixtures.read(PolicyFixtures.RANKING_SHARED));
        PolicySource source = new PolicySource() {
            @Override
            public PolicyVersion<GradingPolicySpec> grading(LocalDate asOf) {
                return new PolicyVersion<>("GRADING-BROKEN", asOf, asOf, contradictory);
            }

            @Override
            public PolicyVersion<RankingPolicySpec> ranking(LocalDate asOf) {
                return new PolicyVersion<>("RANK-X", asOf, asOf, ranking);
            }
        };
        assertThatThrownBy(() -> s.service(source).issue(GradeScenario.request("INS-A:PRD-1001", "INS-B:PRD-2044", "INS-C:PRD-3120")))
                .isInstanceOf(PolicySelfCheckException.class).hasMessageContaining("gradeOrdinal");
        assertThat(s.snapshots.count()).isZero();
    }

    @Test
    void 측정_파라미터_결함은_정책_결함() {
        GradeScenario s = new GradeScenario().ranking(PolicyFixtures.RANKING_SHARED).product("INS-A:PRD-1001", "1");
        s.policies.addGrading("GRADING-BAD", LocalDate.of(2026, 7, 1), null, "ACTIVE",
                PolicyFixtures.read(PolicyFixtures.GRADING_5).replace("\"installmentNo\": null", "\"installment\": 1"));
        assertThatThrownBy(() -> s.service().issue(GradeScenario.request("INS-A:PRD-1001")))
                .isInstanceOf(InvalidPolicyException.class).hasMessageContaining("installmentNo");
    }

    @Test
    void 재조회는_저장된_문자열_그대로_다른_테넌트는_404_변조는_무결성_오류() {
        GradeScenario s = three();
        DisclosureGradeService service = s.service();
        DisclosureGradeService.Issued issued = service.issue(GradeScenario.request("INS-A:PRD-1001", "INS-B:PRD-2044"));
        assertThat(service.refetch(issued.snapshotId())).isEqualTo(issued.responseCanonical());
        assertThatThrownBy(() -> service.refetch("GRD-20260923-999999")).isInstanceOf(SnapshotNotFoundException.class);

        s.instanceTenant = "T2";
        assertThatThrownBy(() -> s.service().refetch(issued.snapshotId())).isInstanceOf(SnapshotNotFoundException.class);

        s.snapshots.tamper(issued.snapshotId(), issued.responseCanonical().replace("\"tie\":false", "\"tie\":true"));
        assertThatThrownBy(() -> service.refetch(issued.snapshotId())).isInstanceOf(SnapshotIntegrityException.class);
    }

    @Test
    void 요청_형식_검증() {
        assertThatThrownBy(() -> GradeRequest.of("T1", GradeScenario.AS_OF, "PG", List.of()))
                .isInstanceOf(GradeRequestException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> GradeRequest.of("T1", GradeScenario.AS_OF, "PG", List.of(
                new GradeRequest.Product("INS-A:P1", "INS-A"), new GradeRequest.Product("INS-A:P1", "INS-A"))))
                .isInstanceOf(GradeRequestException.class).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> GradeRequest.of("t1", GradeScenario.AS_OF, "PG", List.of(new GradeRequest.Product("INS-A:P1", "INS-A"))))
                .isInstanceOf(GradeRequestException.class).hasMessageContaining("tenantId");
        assertThatThrownBy(() -> GradeRequest.of("T1", GradeScenario.AS_OF, "PG", List.of(new GradeRequest.Product("no-colon", "X"))))
                .isInstanceOf(GradeRequestException.class).hasMessageContaining("productKey");
        assertThatThrownBy(() -> GradeRequest.of("T1", null, "PG", List.of(new GradeRequest.Product("INS-A:P1", "INS-A"))))
                .isInstanceOf(GradeRequestException.class).hasMessageContaining("asOfDate");
        assertThat(Map.of()).isEmpty();
    }
}
