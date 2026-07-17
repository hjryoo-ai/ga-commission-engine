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
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", oracle::getJdbcUrl);
        registry.add("spring.datasource.username", oracle::getUsername);
        registry.add("spring.datasource.password", oracle::getPassword);
        // 기본 프로파일 → Flyway가 프레시 컨테이너를 migrate. (운영 prod는 validate-only)
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
