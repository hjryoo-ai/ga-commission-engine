package ga.comm.infra.it;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;
import ga.comm.domain.type.EventType;
import ga.comm.domain.type.RecipientType;
import ga.comm.infra.OraclePersistence;
import org.flywaydb.core.Flyway;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Testcontainers Oracle 하네스 (설계서 §8.7 — Testcontainers 선행 원칙).
 * 컨테이너는 테스트 JVM당 1회 기동하고(싱글턴), 각 테스트는 cleanAll()로 표를 비운다.
 * Flyway는 공통(db/migration) + Oracle 전용(db/vendor/oracle) 위치를 함께 적용한다 —
 * 운영 배포와 동일한 스키마 경로다.
 */
public final class OracleTestSupport {

    private static final String IMAGE = "gvenzl/oracle-free:23-slim-faststart";
    private static final AtomicLong SEQ = new AtomicLong(0);

    private static OracleContainer container;
    private static HikariDataSource dataSource;
    private static OraclePersistence persistence;

    private OracleTestSupport() {
    }

    public static synchronized OraclePersistence persistence() {
        if (persistence == null) {
            start();
        }
        return persistence;
    }

    public static synchronized DataSource dataSource() {
        persistence();
        return dataSource;
    }

    private static void start() {
        container = new OracleContainer(DockerImageName.parse(IMAGE));
        container.start();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(container.getJdbcUrl());
        config.setUsername(container.getUsername());
        config.setPassword(container.getPassword());
        config.setMaximumPoolSize(8); // 동시성 경합 테스트(§8.6)용 커넥션 여유
        dataSource = new HikariDataSource(config);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/vendor/oracle")
                .load()
                .migrate();

        persistence = new OraclePersistence(dataSource);
    }

    /** FK 역순으로 전 표를 비운다 — 계약 테스트의 테스트 간 독립성 확보. */
    public static void cleanAll() {
        persistence();
        List<String> tables = List.of(
                "BATCH_STEP_EXECUTION_CONTEXT", "BATCH_JOB_EXECUTION_CONTEXT",
                "BATCH_STEP_EXECUTION", "BATCH_JOB_EXECUTION_PARAMS",
                "BATCH_JOB_EXECUTION", "BATCH_JOB_INSTANCE",
                "AGENT_SETTLEMENT_CALC", "AGENT_SETTLEMENT", "ADJUSTMENT",
                "CLAWBACK_OFFSET_HIST", "CLAWBACK_RECEIVABLE",
                "DEFERRAL_SCHEDULE",
                "LIMIT_LEDGER_DTL", "LIMIT_LEDGER_HIST", "LIMIT_LEDGER",
                "INBOUND_STATEMENT",
                "COMM_CALC", "POLICY_EVENT", "SETTLE_CLOSE",
                "INCENTIVE_CHANGE_HIST", "INCENTIVE_MST",
                "COMM_RATE_CHANGE_HIST", "COMM_RATE", "LIMIT_RULE",
                "COMM_TYPE_MST", "AGENT_PAYOUT_RATE",
                "DEFERRAL_CURVE_DTL", "DEFERRAL_CURVE", "CLAWBACK_RULE", "ORG_OVERRIDE_RATE",
                "AGENT_GRADE_HIST", "AGENT_ORG_HIST", "AGENT_MST", "ORG_MST",
                // Phase E3: 정책·상품군은 비운다. 스냅샷(DISC_GRADE_SNAPSHOT·_ITEM)은 불변 트리거가 DELETE를 거부하므로
                // 비우지 않는다. 채번은 SEQUENCE(V13)라 테스트를 가로질러 계속 증가한다.
                "DISC_GRADING_POLICY", "DISC_RANKING_POLICY", "DISC_PRODUCT_GROUP_MEMBER", "DISC_PRODUCT_GROUP");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String table : tables) {
                statement.executeUpdate("DELETE FROM " + table);
            }
        } catch (Exception e) {
            throw new IllegalStateException("테스트 데이터 정리 실패", e);
        }
    }

    /** COMM_CALC FK를 만족시키기 위한 실제 POLICY_EVENT 행 생성. */
    public static long newEventId() {
        OraclePersistence p = persistence();
        PolicyEvent event = new PolicyEvent(null, "IT-EVT-" + SEQ.incrementAndGet(),
                new PolicyNo("POL-IT-FK"), new InsurerCode("SAMLIFE"),
                new ProductKey("WHOLE-LIFE-20Y"), EventType.NEW,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), new AgentId("A-1001"),
                Money.won(300_000), null, null, Map.of());
        return p.inTx(() -> p.policyEventStore().upsertByKey(event)).event().eventId();
    }

    /** DTL/스케줄/채권 FK를 만족시키기 위한 실제 COMM_CALC 행 생성. */
    public static long newCalcId() {
        OraclePersistence p = persistence();
        long eventId = newEventId();
        CommCalcRecord record = new CommCalcRecord(null, eventId, new PolicyNo("POL-IT-FK"),
                RecipientType.AGENT, "A-1001", CommTypeCode.FY_COMM, Money.won(100_000), null,
                Money.won(90_000), Money.ZERO, CloseYm.of("202608"), CalcStatus.CALCULATED,
                null, null, null);
        return p.inTx(() -> p.commCalcStore().insert(record)).calcId();
    }
}
