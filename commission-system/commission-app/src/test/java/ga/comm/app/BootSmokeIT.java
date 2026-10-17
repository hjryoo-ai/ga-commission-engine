package ga.comm.app;

import ga.comm.batch.BatchRequestRunner;
import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;
import ga.comm.infra.OraclePersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기동 스모크 E2E (설계서 §10 Phase 16 — 운영 조립 완료 기준). <b>풀 컨텍스트</b>를 실 Oracle
 * (Testcontainers)에 띄워 배선 결함(빈 누락·중복·TM 혼선)을 드러낸다:
 * 마이그레이션 → 룰 시드 → 이벤트 계산 → REST 순액 조회 → 마감 잡 1회. 더해 <b>잡 자동실행 금지</b>와
 * <b>역할 분리</b>를 검증한다. Phase 11의 트랜잭션 구조(업무 TM vs 배치 메타)가 조립 후에도 유지되어
 * 마감 잡이 정상 완료·확정됨을 확인한다.
 */
@SpringBootTest(classes = CommissionApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(org.junit.jupiter.api.MethodOrderer.MethodName.class)
class BootSmokeIT {

    @Container
    static OracleContainer oracle =
            new OracleContainer(DockerImageName.parse(java.util.Objects.requireNonNull(System.getProperty("engine.oracleImage"),
                    "engine.oracleImage(Gradle이 gradle/libs.versions.toml의 oracle-free-image로 준다)")));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", oracle::getJdbcUrl);
        registry.add("spring.datasource.username", oracle::getUsername);
        registry.add("spring.datasource.password", oracle::getPassword);
        // 기본 프로파일 → Flyway가 프레시 컨테이너를 migrate. (운영 prod는 validate-only)
        // Phase E3: 엔진 인스턴스 테넌트 + 내부 API 서비스 토큰의 SHA-256(원문은 테스트만 안다)
        registry.add("app.tenant-id", () -> "T1");
        registry.add("app.internal.service-token-sha256", () -> sha256Hex(SERVICE_TOKEN));
    }

    static final String SERVICE_TOKEN = "it-service-token-" + BootSmokeIT.class.getSimpleName();

    static String sha256Hex(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    DataSource dataSource;
    @Autowired
    OraclePersistence persistence;
    @Autowired
    CommissionCalculator calculator;
    @Autowired
    BatchRequestRunner batchRequestRunner;
    @Autowired
    @Qualifier("monthCloseJob")
    Job monthCloseJob;
    @Autowired
    @Qualifier("outboxAgentSnapshotJob")
    Job outboxAgentSnapshotJob;

    @Test
    void 기동_스모크_E2E() throws Exception {
        // 0. 잡 자동실행 금지 — 컨텍스트가 떠도 어떤 잡도 실행되지 않았다
        assertThat(batchExecutionCount()).as("기동 시 잡 자동실행 금지").isZero();

        // 1. 룰 시드 (Oracle ruleRepository/agentDirectory가 조회할 마스터)
        seedStandardRules();

        // 2. 이벤트 적재 + 계산 (실 파이프라인·실 Oracle 스토어, 트랜잭션 경계는 inTx)
        persistence.inTx(() -> calculator.process(EventFixtures.newContract()));

        // 3. REST 순액 조회 (VIEWER) — 파사드가 NetAmountCalculator로 산출한 순액
        mvc.perform(get("/api/commissions/policies/{p}/agents/{a}", "POL-2026-0001", "A-1001")
                        .with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netAmount").value(1_890_000))
                .andExpect(jsonPath("$.limit.limitAmount").value(3_600_000))
                .andExpect(jsonPath("$.limit.accumPaid").value(1_890_000));

        // 4. 마감 잡 1회 — 업무 TM으로 원자적 확정, 배치 메타는 같은 DataSource. COMPLETED로 수렴
        JobParameters params = new JobParametersBuilder()
                .addString("close_ym", "202608")
                .addString("closed_by", "정산담당")
                .addString("force", "N")
                .toJobParameters();
        BatchRequestRunner.Outcome outcome = batchRequestRunner.submit(monthCloseJob, params);
        assertThat(outcome.disposition()).isEqualTo(BatchRequestRunner.Disposition.COMPLETED);

        // 마감 후 레코드 CONFIRMED — 배선된 마감 서비스가 실제로 상태를 전이시켰다
        assertThat(persistence.inTx(() -> persistence.commCalcStore()
                .findByCloseYm(CloseYm.of("202608"))))
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(CalcStatus.CONFIRMED));

        // 5. 역할 분리 — VIEWER는 승인 API(ADMIN 전용)에 접근할 수 없다
        mvc.perform(post("/api/rates/{id}/approve", 1L).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden());
    }

    /**
     * Phase E3 E2E: 풀 컨텍스트(실 Oracle·실 보안 체인)에서 비교설명 등급 API. 토큰 없음·틀림 401, 테넌트 불일치 403,
     * 정상 200 → GET 재조회 바이트 동일, 기본 체인의 Basic 사용자는 /internal/**에 들어올 수 없다.
     */
    @Test
    void 비교설명_등급_API_E2E() throws Exception {
        exec("INSERT INTO DISC_PRODUCT_GROUP (group_code_system, group_code, group_name, apply_from) "
                + "VALUES ('PG-V1', 'PG-HEALTH-SIMPLE-NR', '(가상) 간편', DATE '2026-01-01')");
        String[][] products = {{"INS-A", "PRD-1001", "0.84"}, {"INS-B", "PRD-2044", "1.37"}, {"INS-C", "PRD-3120", "1.02"}};
        for (String[] p : products) {
            exec("INSERT INTO DISC_PRODUCT_GROUP_MEMBER (group_code_system, group_code, ext_product_key, insurer_cd, product_key, "
                    + "apply_from) VALUES ('PG-V1', 'PG-HEALTH-SIMPLE-NR', '" + p[0] + ":" + p[1] + "', '" + p[0] + "', '" + p[1]
                    + "', DATE '2026-01-01')");
            exec("INSERT INTO COMM_RATE (direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, "
                    + "version_no, status) VALUES ('INBOUND', '" + p[0] + "', '" + p[1] + "', 'FY_COMM', NULL, " + p[2]
                    + ", DATE '2026-01-01', 1, 'ACTIVE')");
        }
        policy("DISC_GRADING_POLICY", "GRADING-2026-07", ga.comm.disclosure.grade.fixture.PolicyFixtures.GRADING_5);
        policy("DISC_RANKING_POLICY", "RANK-2026-07", ga.comm.disclosure.grade.fixture.PolicyFixtures.RANKING_SHARED);

        String url = "/internal/v1/disclosure/commission-grades";
        String body = "{\"tenantId\":\"T1\",\"asOfDate\":\"2026-09-23\",\"productGroupCode\":\"PG-HEALTH-SIMPLE-NR\","
                + "\"products\":[{\"productKey\":\"INS-A:PRD-1001\",\"insurerCode\":\"INS-A\"},"
                + "{\"productKey\":\"INS-B:PRD-2044\",\"insurerCode\":\"INS-B\"},"
                + "{\"productKey\":\"INS-C:PRD-3120\",\"insurerCode\":\"INS-C\"}]}";

        // 401: 토큰 없음 / 틀린 토큰 / Basic 사용자(사람 계정)
        mvc.perform(post(url).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(post(url).contentType("application/json").content(body).header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(url).contentType("application/json").content(body).header("Authorization", "Basic "
                        + java.util.Base64.getEncoder().encodeToString("admin:admin".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .andExpect(status().isUnauthorized());
        // 403: 토큰은 맞지만 다른 테넌트
        mvc.perform(post(url).contentType("application/json").content(body.replace("\"T1\"", "\"T2\""))
                        .header("Authorization", "Bearer " + SERVICE_TOKEN))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_MISMATCH"));
        // 200 → GET 바이트 동일
        org.springframework.test.web.servlet.MvcResult issued = mvc.perform(post(url).contentType("application/json").content(body)
                        .header("Authorization", "Bearer " + SERVICE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].productKey").value("INS-A:PRD-1001"))
                .andExpect(jsonPath("$.results[0].ratioToAvg").value("0.78"))
                .andReturn();
        String snapshotId = com.jayway.jsonpath.JsonPath.read(issued.getResponse().getContentAsString(), "$.snapshotId");
        assertThat(snapshotId).matches("GRD-\\d{8}-\\d{7}");
        byte[] refetched = mvc.perform(get(url + "/{id}", snapshotId).header("Authorization", "Bearer " + SERVICE_TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(refetched).isEqualTo(issued.getResponse().getContentAsByteArray());
        mvc.perform(get(url + "/{id}", snapshotId)).andExpect(status().isUnauthorized());
    }

    /**
     * Phase E1 C7: 풀 컨텍스트(실 Oracle·실 보안 체인)에서 이벤트 피드. 앞 시험(메서드 이름 순)의 계산·마감이 낸 이벤트가 lazy 부여로 빈틈 없는
     * 순번을 받아 나오고, 응답은 포털 계약 사본의 FeedResponse를 통과한다. 토큰 없음 401, 헤드 너머 422, AgentSnapshot 부트스트랩 잡.
     */
    @Test
    void 이벤트_피드_E2E() throws Exception {
        String url = "/internal/v1/events";
        String bearer = "Bearer " + SERVICE_TOKEN;
        mvc.perform(get(url).param("afterSeq", "0")).andExpect(status().isUnauthorized());
        mvc.perform(get(url).param("afterSeq", "0").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get(url).param("afterSeq", "0").header("Authorization", "Basic " + java.util.Base64.getEncoder()
                .encodeToString("admin:admin".getBytes(java.nio.charset.StandardCharsets.UTF_8)))).andExpect(status().isUnauthorized());

        com.fasterxml.jackson.databind.JsonNode first = feed(get(url).param("afterSeq", "0").param("limit", "1000")
                .header("Authorization", bearer));
        assertThat(first.path("tenantId").asText()).isEqualTo("T1");
        java.util.List<Long> seqs = new java.util.ArrayList<>();
        java.util.Set<String> types = new java.util.TreeSet<>();
        first.path("events").forEach(e -> {
            seqs.add(e.path("seq").asLong());
            types.add(e.path("type").asText());
        });
        assertThat(seqs).isNotEmpty();
        assertThat(seqs).isEqualTo(java.util.stream.LongStream.rangeClosed(1, seqs.size()).boxed().toList());   // 갭 0
        assertThat(types).contains("CommissionCalculated", "LimitLedgerOpened", "LimitLedgerPosted", "StatementConfirmed");
        long head = first.path("headSeq").asLong();
        assertThat(first.path("nextSeq").asLong()).isEqualTo(head);

        mvc.perform(get(url).param("afterSeq", Long.toString(head + 1)).header("Authorization", bearer))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("AFTER_BEYOND_HEAD"));
        mvc.perform(get(url).header("Authorization", bearer))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // AgentSnapshot 부트스트랩 잡(배치 기동 경로와 같은 잡 빈)
        BatchRequestRunner.Outcome snapshot = batchRequestRunner.submit(outboxAgentSnapshotJob, new JobParametersBuilder()
                .addString("as_of", "2026-09-01").addString("request_id", "BOOT-1").toJobParameters());
        assertThat(snapshot.disposition()).isEqualTo(BatchRequestRunner.Disposition.COMPLETED);
        com.fasterxml.jackson.databind.JsonNode next = feed(get(url).param("afterSeq", Long.toString(head))
                .header("Authorization", bearer));
        assertThat(next.path("events")).hasSize(1);
        com.fasterxml.jackson.databind.JsonNode agent = next.path("events").get(0);
        assertThat(agent.path("seq").asLong()).isEqualTo(head + 1);
        assertThat(agent.path("type").asText()).isEqualTo("AgentSnapshot");
        assertThat(agent.path("payload").path("orgPath").asText()).isEqualTo("/H1/B1/T1");

        // C8 이벤트 쪽 마감 불변식(P11): 8월은 첫 시험에서 마감됐다 — 8월 날짜로 늦게 들어온 신계약은 운영 리졸버가 9월로 보낸다
        persistence.inTx(() -> calculator.process(EventFixtures.newContract(new ga.comm.domain.id.PolicyNo("POL-2026-0009"),
                java.time.LocalDate.of(2026, 8, 20), ga.comm.domain.money.Money.won(300_000), java.util.Map.of())));
        com.fasterxml.jackson.databind.JsonNode late = feed(get(url).param("afterSeq", Long.toString(head + 1))
                .header("Authorization", bearer));
        java.util.List<com.fasterxml.jackson.databind.JsonNode> lateCalcs = new java.util.ArrayList<>();
        late.path("events").forEach(ev -> {
            if (ev.path("type").asText().equals("CommissionCalculated")) {
                lateCalcs.add(ev);
            }
        });
        com.fasterxml.jackson.databind.JsonNode all = feed(get(url).param("afterSeq", "0").param("limit", "1000")
                .header("Authorization", bearer));
        java.util.List<com.fasterxml.jackson.databind.JsonNode> envelopes = new java.util.ArrayList<>();
        all.path("events").forEach(envelopes::add);
        assertThat(ga.comm.outbox.fixture.CloseInvariant.violations(envelopes)).isEmpty();
        assertThat(lateCalcs).isNotEmpty().allSatisfy(ev -> assertThat(ev.path("payload").path("closeYm").asText()).isEqualTo("202609"));
    }

    private com.fasterxml.jackson.databind.JsonNode feed(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        byte[] body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        assertThat(ga.comm.outbox.contract.PortalContract.get().validateApiSchema("FeedResponse", json)).as(json.toString()).isEmpty();
        return json;
    }

    private void policy(String table, String id, String fixture) throws Exception {
        try (Connection c = dataSource.getConnection(); java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO " + table
                + " (policy_version_id, apply_from, status, body, created_by) VALUES (?, DATE '2026-07-01', 'ACTIVE', ?, 'it')")) {
            ps.setString(1, id);
            ps.setString(2, ga.comm.disclosure.grade.fixture.PolicyFixtures.read(fixture));
            ps.executeUpdate();
        }
    }

    private long batchExecutionCount() throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM BATCH_JOB_EXECUTION")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 최소 표준 룰·설계사·조직 시드 (신계약 FY 라인 + 오버라이드 + 한도 원장이 나오도록). */
    private void seedStandardRules() throws Exception {
        exec("INSERT INTO COMM_TYPE_MST (comm_type, apply_from, limit_included, rounding_policy, clawback_target) "
                + "VALUES ('FY_COMM', DATE '2026-01-01', 'Y', 'KRW_FLOOR', 'Y')");
        exec("INSERT INTO COMM_TYPE_MST (comm_type, apply_from, limit_included, rounding_policy, clawback_target) "
                + "VALUES ('OVERRIDE', DATE '2026-01-01', 'N', 'KRW_FLOOR', 'N')");
        exec("INSERT INTO COMM_RATE (direction, insurer_cd, product_key, comm_type, installment_no, rate, "
                + "apply_from, version_no, status) VALUES ('OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'FY_COMM', "
                + "NULL, 7.0, DATE '2026-01-01', 1, 'ACTIVE')");
        exec("INSERT INTO AGENT_PAYOUT_RATE (grade_cd, comm_type, payout_rate, apply_from) "
                + "VALUES ('SR', 'FY_COMM', 0.9, DATE '2026-01-01')");
        exec("INSERT INTO ORG_OVERRIDE_RATE (org_level, comm_type, override_rate, apply_from) "
                + "VALUES ('TEAM', 'FY_COMM', 0.05, DATE '2026-01-01')");
        exec("INSERT INTO ORG_OVERRIDE_RATE (org_level, comm_type, override_rate, apply_from) "
                + "VALUES ('BRANCH', 'FY_COMM', 0.03, DATE '2026-01-01')");
        exec("INSERT INTO ORG_OVERRIDE_RATE (org_level, comm_type, override_rate, apply_from) "
                + "VALUES ('HQ', 'FY_COMM', 0.02, DATE '2026-01-01')");
        exec("INSERT INTO LIMIT_RULE (channel_type, limit_multiple, fy_window_months, over_limit_action, "
                + "clawback_restores, apply_from) VALUES ('GA_TO_AGENT', 12.00, 12, 'DEFER_AFTER_FY', 'Y', "
                + "DATE '2026-07-01')");
        exec("INSERT INTO ORG_MST (org_id, org_name, org_level) VALUES ('T1', '팀1', 'TEAM')");
        exec("INSERT INTO ORG_MST (org_id, org_name, org_level) VALUES ('B1', '지점1', 'BRANCH')");
        exec("INSERT INTO ORG_MST (org_id, org_name, org_level) VALUES ('H1', '본부1', 'HQ')");
        exec("INSERT INTO AGENT_MST (agent_id, agent_name, appointed_on) "
                + "VALUES ('A-1001', '설계사A', DATE '2025-01-01')");
        exec("INSERT INTO AGENT_GRADE_HIST (agent_id, grade_cd, apply_from) "
                + "VALUES ('A-1001', 'SR', DATE '2025-01-01')");
        for (String org : new String[]{"T1", "B1", "H1"}) {
            exec("INSERT INTO AGENT_ORG_HIST (agent_id, org_id, apply_from) "
                    + "VALUES ('A-1001', '" + org + "', DATE '2025-01-01')");
        }
    }

    private void exec(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }
}
