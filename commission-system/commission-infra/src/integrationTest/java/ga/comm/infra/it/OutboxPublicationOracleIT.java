package ga.comm.infra.it;

import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.EventType;
import ga.comm.domain.type.RecipientType;
import ga.comm.infra.OraclePersistence;
import ga.comm.outbox.feed.AgentSnapshotPublisher;
import ga.comm.outbox.fixture.OutboxEngine;
import ga.comm.outbox.publish.OutboxAdjustmentStore;
import ga.comm.outbox.publish.OutboxClawbackReceivableStore;
import ga.comm.outbox.publish.OutboxCommCalcStore;
import ga.comm.outbox.publish.OutboxDeferralScheduleStore;
import ga.comm.outbox.publish.OutboxLimitLedgerStore;
import ga.comm.outbox.publish.OutboxPolicyEventStore;
import ga.comm.outbox.publish.PayoutPublisher;
import ga.comm.outbox.publish.StatementPublisher;
import ga.comm.rule.fixture.RuleFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E1 C3 — 발행 지점마다(설계서 §6.8 발행 지점 표 12행 — 업무 경로 11 + AgentSnapshot) 실제 Oracle에서: 업무 트랜잭션 안에서는 그 이벤트가
 * 적재돼 보이고(공허한 시험이 아님), 업무가 롤백되면 {@code OUTBOX_EVENT}에 아무것도 남지 않는다. 같은 일을 커밋하면 남는다(대조).
 * 발행을 별도 트랜잭션으로 옮기면 롤백 뒤에도 행이 남아 실패한다(주입 I3).
 */
class OutboxPublicationOracleIT {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-05T09:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final PolicyNo POLICY_2 = new PolicyNo("POL-2026-0002");
    private static final LocalDate CONTRACT_2027 = LocalDate.of(2027, 1, 10);

    private final OraclePersistence p = OracleTestSupport.persistence();
    private final JdbcTemplate jdbc = new JdbcTemplate(OracleTestSupport.dataSource());
    private OutboxEngine e;

    /** 강제 롤백 신호. */
    private static final class Rollback extends RuntimeException {
        Rollback() {
            super("강제 롤백(C3)");
        }
    }

    @BeforeEach
    void setUp() {
        OracleTestSupport.cleanAll();
        e = new OutboxEngine(RuleFixtures.standardRules(), CalcTestHarness.standardDirectory(),
                new OutboxEngine.Raw(p.policyEventStore(), p.commCalcStore(), p.limitLedgerStore(), p.deferralScheduleStore(),
                        p.clawbackReceivableStore(), p.adjustmentStore(), p.settleCloseStore(), p.agentSettlementStore(),
                        p.payoutStatementStore(), p.outboxStore()),
                CLOCK, UUID::randomUUID);
    }

    private long rows(String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM OUTBOX_EVENT WHERE event_type = ?", Long.class, type);
    }

    private long rows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM OUTBOX_EVENT", Long.class);
    }

    /** 업무 {@code work}를 한 트랜잭션에서 돌리고 강제 롤백 — 안에서는 {@code types}가 적재돼 보였고, 밖에서는 아무 행도 남지 않았다. */
    private void rolledBack(Runnable work, String... types) {
        long before = rows();
        Map<String, Long> typeBefore = new java.util.HashMap<>();
        for (String t : types) {
            typeBefore.put(t, rows(t));
        }
        assertThatThrownBy(() -> p.inTx(() -> {
            work.run();
            for (String t : types) {
                assertThat(rows(t)).as("트랜잭션 안에서 " + t + " 적재").isGreaterThan(typeBefore.get(t));
            }
            throw new Rollback();
        })).isInstanceOf(Rollback.class);
        assertThat(rows()).as("롤백 뒤 OUTBOX_EVENT 행 수").isEqualTo(before);
    }

    /** 대조: 같은 일을 커밋하면 행이 남는다. */
    private void committed(Runnable work, String... types) {
        Map<String, Long> typeBefore = new java.util.HashMap<>();
        for (String t : types) {
            typeBefore.put(t, rows(t));
        }
        p.inTx(work);
        for (String t : types) {
            assertThat(rows(t)).as("커밋 뒤 " + t).isGreaterThan(typeBefore.get(t));
        }
    }

    @Test
    void 계산_원장_개설_전기() {
        Runnable work = () -> e.calculator.process(EventFixtures.newContract());
        String[] types = {OutboxCommCalcStore.TYPE, OutboxLimitLedgerStore.OPENED, OutboxLimitLedgerStore.POSTED};
        rolledBack(work, types);
        committed(work, types);
    }

    @Test
    void 정책_이벤트() {
        Runnable work = () -> e.stores.events().upsertByKey(EventFixtures.terminal(EventType.LAPSE, EventFixtures.POLICY_1,
                EventFixtures.CONTRACT_DATE, LocalDate.of(2026, 9, 10), 2));
        rolledBack(work, OutboxPolicyEventStore.TYPE);
        committed(work, OutboxPolicyEventStore.TYPE);
    }

    @Test
    void 한도_재산정() {
        p.inTx(() -> e.calculator.process(EventFixtures.newContract()));
        Runnable work = () -> e.calculator.process(EventFixtures.reduce(EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE,
                LocalDate.of(2026, 9, 10), Money.won(200_000)));
        rolledBack(work, OutboxLimitLedgerStore.REBASED);
        committed(work, OutboxLimitLedgerStore.REBASED);
    }

    @Test
    void 분급_생성과_도래_전이() {
        Runnable create = () -> e.calculator.process(EventFixtures.newContract(POLICY_2, CONTRACT_2027, Money.won(300_000), Map.of()));
        rolledBack(create, OutboxDeferralScheduleStore.SCHEDULED);
        committed(create, OutboxDeferralScheduleStore.SCHEDULED);

        Runnable release = () -> e.releaseService.release(CloseYm.of("202801"));
        rolledBack(release, OutboxDeferralScheduleStore.STATUS_CHANGED, OutboxCommCalcStore.TYPE);
        committed(release, OutboxDeferralScheduleStore.STATUS_CHANGED, OutboxCommCalcStore.TYPE);
    }

    @Test
    void 정정_reversal과_분급_취소() {
        p.inTx(() -> e.calculator.process(EventFixtures.newContract(POLICY_2, CONTRACT_2027, Money.won(300_000), Map.of())));
        long fy = p.inTx(() -> p.commCalcStore().findByPolicyAndRecipient(POLICY_2, "A-1001")).get(0).calcId();
        Runnable work = () -> e.revision.reverse(fy, "요율 정정");
        rolledBack(work, OutboxCommCalcStore.TYPE, OutboxDeferralScheduleStore.STATUS_CHANGED, OutboxLimitLedgerStore.POSTED);
        committed(work, OutboxCommCalcStore.TYPE, OutboxDeferralScheduleStore.STATUS_CHANGED, OutboxLimitLedgerStore.POSTED);
    }

    @Test
    void 마감_확정() {
        p.inTx(() -> e.calculator.process(EventFixtures.newContract()));
        Runnable work = () -> e.closeService.close(CloseYm.of("202608"), "정산담당");
        rolledBack(work, StatementPublisher.TYPE);
        committed(work, StatementPublisher.TYPE);
    }

    @Test
    void 지급_확정과_채권() {
        p.inTx(() -> e.calculator.process(EventFixtures.newContract()));
        p.inTx(() -> e.closeService.close(CloseYm.of("202608"), "정산담당"));
        Runnable pay = () -> e.payoutService.run(CloseYm.of("202608"), 1);
        rolledBack(pay, PayoutPublisher.TYPE);
        committed(pay, PayoutPublisher.TYPE);

        // 9월 해약 → 순액 음수 → 지급 런에서 채권화
        p.inTx(() -> e.calculator.process(EventFixtures.terminal(EventType.CANCEL, EventFixtures.POLICY_1,
                EventFixtures.CONTRACT_DATE, LocalDate.of(2026, 9, 15), 2)));
        p.inTx(() -> e.closeService.close(CloseYm.of("202609"), "정산담당"));
        Runnable capitalize = () -> e.payoutService.run(CloseYm.of("202609"), 1);
        rolledBack(capitalize, OutboxClawbackReceivableStore.TYPE, PayoutPublisher.TYPE);
        committed(capitalize, OutboxClawbackReceivableStore.TYPE, PayoutPublisher.TYPE);
    }

    @Test
    void 마감_후_정정() {
        p.inTx(() -> e.calculator.process(EventFixtures.newContract()));
        p.inTx(() -> e.closeService.close(CloseYm.of("202608"), "정산담당"));
        long fy = p.inTx(() -> p.commCalcStore().findByCloseYm(CloseYm.of("202608"))).stream()
                .filter(r -> r.recipientType() == RecipientType.AGENT).findFirst().orElseThrow().calcId();
        Runnable work = () -> e.adjustmentService.adjust(fy, "소급 요율 정정", Money.won(-45_000), "정산팀장");
        rolledBack(work, OutboxAdjustmentStore.TYPE);
        committed(work, OutboxAdjustmentStore.TYPE);

        // 정정은 다음 달 명세에 합산된다(Oracle 조회 경로 — ADJUSTMENT.findByCloseYm)
        p.inTx(() -> e.closeService.close(CloseYm.of("202609"), "정산담당"));
        String payload = jdbc.queryForObject("SELECT payload FROM OUTBOX_EVENT WHERE event_type = 'StatementConfirmed' "
                + "AND aggregate_id = 'A-1001:202609' ORDER BY event_no DESC FETCH FIRST 1 ROWS ONLY", String.class);
        assertThat(payload).contains("\"adjustment\":-45000");
        List<String> kinds = jdbc.queryForList("SELECT DISTINCT aggregate_kind FROM OUTBOX_EVENT WHERE event_type = 'AdjustmentPosted'",
                String.class);
        assertThat(kinds).containsExactly("ADJUSTMENT");
    }

    @Test
    void 설계사_스냅샷() {
        jdbc.update("INSERT INTO ORG_MST (org_id, org_name, org_level) VALUES ('H1', '본부1', 'HQ')");
        jdbc.update("INSERT INTO ORG_MST (org_id, org_name, org_level, parent_org) VALUES ('B1', '지점1', 'BRANCH', 'H1')");
        jdbc.update("INSERT INTO ORG_MST (org_id, org_name, org_level, parent_org) VALUES ('T1', '팀1', 'TEAM', 'B1')");
        jdbc.update("INSERT INTO AGENT_MST (agent_id, agent_name, appointed_on) VALUES ('A-1001', '설계사A', DATE '2024-03-01')");
        jdbc.update("INSERT INTO AGENT_GRADE_HIST (agent_id, grade_cd, apply_from) VALUES ('A-1001', 'SR', DATE '2025-01-01')");
        for (String org : new String[]{"T1", "B1", "H1"}) {
            jdbc.update("INSERT INTO AGENT_ORG_HIST (agent_id, org_id, apply_from) VALUES ('A-1001', ?, DATE '2025-06-01')", org);
        }
        AgentSnapshotPublisher snapshots = new AgentSnapshotPublisher(p.agentMasterReader(), p.agentDirectory(), e.publisher);
        assertThat(p.inTx(snapshots::agentIds)).containsExactly("A-1001");

        Runnable work = () -> snapshots.publish("A-1001", LocalDate.of(2026, 9, 1));
        rolledBack(work, AgentSnapshotPublisher.TYPE);
        committed(work, AgentSnapshotPublisher.TYPE);
        String payload = jdbc.queryForObject("SELECT payload FROM OUTBOX_EVENT WHERE event_type = 'AgentSnapshot' "
                + "ORDER BY event_no DESC FETCH FIRST 1 ROWS ONLY", String.class);
        assertThat(payload).contains("\"orgPath\":\"/H1/B1/T1\"", "\"grade\":\"SR\"", "\"validFrom\":\"2025-06-01\"",
                "\"status\":\"ACTIVE\"", "\"name\":\"설계사A\"");
    }
}
