package ga.comm.infra.it;

import com.fasterxml.jackson.databind.JsonNode;
import ga.comm.disclosure.grade.DisclosureGradeService;
import ga.comm.disclosure.grade.GradeRequest;
import ga.comm.disclosure.grade.fixture.PolicyFixtures;
import ga.comm.disclosure.grade.snapshot.SnapshotIntegrityException;
import ga.comm.disclosure.grade.snapshot.SnapshotJson;
import ga.comm.disclosure.grade.snapshot.SnapshotNotFoundException;
import ga.comm.infra.OraclePersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E7(Oracle Testcontainers): 재조회는 발급 응답과 <b>바이트 동일</b>하고(저장 해시 대조 포함), 정책 버전이 바뀐 뒤 재조회해도
 * 옛 스냅샷 문자열은 그대로다. E9의 Oracle 쪽: 응답·DB 항목(ratio_to_avg)·재조회의 ratioToAvg 삼자 동일.
 */
class SnapshotRefetchIT {

    private static final String TENANT = "T1";
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 23);

    private final OraclePersistence persistence = OracleTestSupport.persistence();
    private final DisclosureSeed seed = new DisclosureSeed(OracleTestSupport.dataSource());
    private Clock clock;

    @BeforeEach
    void seed() {
        OracleTestSupport.cleanAll();
        // 스냅샷·채번 표는 비우지 않으므로(불변) 같은 날 번호는 테스트를 가로질러 계속 증가한다
        clock = Clock.fixed(Instant.parse("2026-09-23T01:15:30Z"), ZoneId.of("Asia/Seoul"));
        seed.group()
                .product("INS-A:PRD-1001", "0.840000")
                .product("INS-B:PRD-2044", "1.370000")
                .product("INS-C:PRD-3120", "1.020000")
                .product("INS-D:PRD-4001", "1.020000")
                .grading("GRADING-2026-07", LocalDate.of(2026, 7, 1), null, "ACTIVE", PolicyFixtures.GRADING_5)
                .ranking("RANK-2026-07", LocalDate.of(2026, 7, 1), null, "ACTIVE", PolicyFixtures.RANKING_SHARED);
    }

    private DisclosureGradeService service() {
        return DisclosureGradeOracleSupport.service(persistence, clock, TENANT);
    }

    private static GradeRequest request(String... keys) {
        return GradeRequest.of(TENANT, AS_OF, DisclosureSeed.GROUP,
                java.util.Arrays.stream(keys).map(k -> new GradeRequest.Product(k, k.substring(0, k.indexOf(':')))).toList());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void 재조회는_바이트_동일하고_저장_해시와_일치한다() throws Exception {
        DisclosureGradeService service = service();
        DisclosureGradeService.Issued issued = service.issue(request("INS-A:PRD-1001", "INS-B:PRD-2044", "INS-C:PRD-3120",
                "INS-D:PRD-4001", "INS-Z:TEMP-7"));
        String refetched = persistence.inTx(() -> service.refetch(issued.snapshotId()));

        assertThat(refetched.getBytes(StandardCharsets.UTF_8)).isEqualTo(issued.responseCanonical().getBytes(StandardCharsets.UTF_8));
        Map<String, String> row = seed.query("SELECT tenant_id, response_sha256, response_canonical FROM DISC_GRADE_SNAPSHOT"
                + " WHERE snapshot_id = ?", issued.snapshotId()).getFirst();
        assertThat(row.get("response_sha256")).isEqualTo(sha256(issued.responseCanonical().getBytes(StandardCharsets.UTF_8)));
        assertThat(row.get("response_canonical")).isEqualTo(issued.responseCanonical());
        assertThat(row.get("tenant_id")).isEqualTo(TENANT);

        JsonNode response = SnapshotJson.mapper().readTree(issued.responseCanonical());
        assertThat(response.get("results").findValuesAsText("rankInSet")).containsExactly("1", "2", "2", "4");
        assertThat(response.get("basis").get("groupPopulation").asInt()).isEqualTo(4);

        // E9 삼자: 응답 ratioToAvg = DB 항목 ratio_to_avg(VARCHAR2 원문) = 재조회 ratioToAvg
        Map<String, String> fromResponse = ratios(response);
        List<Map<String, String>> items = seed.query("SELECT product_key, status, ratio_to_avg, reason FROM DISC_GRADE_SNAPSHOT_ITEM"
                + " WHERE snapshot_id = ? ORDER BY item_order", issued.snapshotId());
        Map<String, String> fromDb = items.stream().filter(i -> i.get("status").equals("OK"))
                .collect(Collectors.toMap(i -> i.get("product_key"), i -> i.get("ratio_to_avg")));
        Map<String, String> fromRefetch = ratios(SnapshotJson.mapper().readTree(refetched));
        assertThat(fromResponse).hasSize(4).isEqualTo(fromDb).isEqualTo(fromRefetch);
        assertThat(items).extracting(i -> i.get("status")).containsExactly("OK", "OK", "OK", "OK", "UNAVAILABLE");
        assertThat(items.get(4).get("ratio_to_avg")).isNull();
        assertThat(items.get(4).get("reason")).isEqualTo("NOT_IN_GROUP");
    }

    @Test
    void 정책이_바뀐_뒤에도_옛_스냅샷_문자열은_그대로다() throws Exception {
        DisclosureGradeService.Issued before = service().issue(request("INS-A:PRD-1001", "INS-B:PRD-2044", "INS-C:PRD-3120"));

        // 정책 교체: 옛 버전 SUPERSEDED, 4단계 새 버전 ACTIVE(같은 적용일부터)
        seed.exec("UPDATE DISC_GRADING_POLICY SET status = 'SUPERSEDED' WHERE policy_version_id = 'GRADING-2026-07'");
        seed.grading("GRADING-2026-07-R1", LocalDate.of(2026, 7, 1), null, "ACTIVE", PolicyFixtures.GRADING_4);
        // 요율도 바뀐다 — 옛 스냅샷은 재계산하지 않으므로 영향이 없어야 한다
        seed.exec("UPDATE COMM_RATE SET rate = 2.000000 WHERE insurer_cd = 'INS-A'");

        DisclosureGradeService service = service();
        String refetched = persistence.inTx(() -> service.refetch(before.snapshotId()));
        assertThat(refetched).isEqualTo(before.responseCanonical());

        DisclosureGradeService.Issued after = service.issue(request("INS-A:PRD-1001", "INS-B:PRD-2044", "INS-C:PRD-3120"));
        assertThat(SnapshotJson.mapper().readTree(after.responseCanonical()).get("gradingPolicyVersionId").asText())
                .isEqualTo("GRADING-2026-07-R1");
        assertThat(after.responseCanonical()).isNotEqualTo(before.responseCanonical());
        assertThat(persistence.inTx(() -> service.refetch(before.snapshotId()))).isEqualTo(before.responseCanonical());
    }

    @Test
    void 없는_스냅샷과_다른_테넌트는_404_원문_변조는_무결성_오류() {
        DisclosureGradeService.Issued issued = service().issue(request("INS-A:PRD-1001"));
        assertThatThrownBy(() -> service().refetch("GRD-19990101-000001")).isInstanceOf(SnapshotNotFoundException.class);
        assertThatThrownBy(() -> DisclosureGradeOracleSupport.service(persistence, clock, "T2").refetch(issued.snapshotId()))
                .isInstanceOf(SnapshotNotFoundException.class);
        // 트리거를 우회한 변조를 가정: 해시가 다른 원문을 새 행으로 넣고 재조회 → 반환하지 않는다
        String forged = "GRD-19990101-000001";
        seed.exec("INSERT INTO DISC_GRADE_SNAPSHOT (snapshot_id, tenant_id, as_of_date, product_group_code, grading_policy_version_id,"
                + " ranking_policy_version_id, tie_break, basis_json, generated_at, response_canonical, response_sha256)"
                + " VALUES (?, 'T1', DATE '1999-01-01', 'PG', 'G', 'R', 'STRICT', '{}', SYSTIMESTAMP, '{\"a\":1}', ?)",
                forged, "0".repeat(64));
        assertThatThrownBy(() -> service().refetch(forged)).isInstanceOf(SnapshotIntegrityException.class);
    }

    private static Map<String, String> ratios(JsonNode response) {
        Map<String, String> map = new java.util.HashMap<>();
        response.get("results").forEach(n -> {
            if (n.has("ratioToAvg")) {
                map.put(n.get("productKey").asText(), n.get("ratioToAvg").textValue());
            }
        });
        return map;
    }
}
