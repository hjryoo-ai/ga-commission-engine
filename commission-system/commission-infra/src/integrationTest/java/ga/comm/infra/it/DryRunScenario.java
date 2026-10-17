package ga.comm.infra.it;

import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.EventType;
import ga.comm.infra.OraclePersistence;
import ga.comm.outbox.feed.AgentMasterReader;
import ga.comm.outbox.feed.EventFeed;
import ga.comm.outbox.feed.StatementQuery;
import ga.comm.outbox.fixture.InMemoryAgentMasterReader;
import ga.comm.outbox.fixture.OutboxEngine;
import ga.comm.settlement.WithholdingTaxPolicy;
import ga.comm.rule.fixture.InMemoryIncentiveStore;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.settlement.simulation.DryRunSimulator;
import ga.comm.settlement.simulation.SimulationModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * E2 C1·C3 공용 Oracle 시나리오 — 202608 계약·마감·지급, 202609 해약(음수 순액 → 채권)·마감·지급, 202610 새 계약(열린 달 — 잠정 명세 대상,
 * 채권 상계 미리보기), 2027 분급 계약(도래 미리보기 재료). 그리고 업무 표의 전 행 해시.
 */
final class DryRunScenario {

    /** 쓰기가 없어야 하는 업무 표(C1). */
    static final List<String> BUSINESS_TABLES = List.of("COMM_CALC", "LIMIT_LEDGER", "LIMIT_LEDGER_DTL", "DEFERRAL_SCHEDULE",
            "CLAWBACK_RECEIVABLE", "CLAWBACK_OFFSET_HIST", "AGENT_SETTLEMENT", "AGENT_SETTLEMENT_CALC", "PAYOUT_STATEMENT", "POLICY_EVENT",
            "SETTLE_CLOSE", "ADJUSTMENT");

    static final CloseYm OPEN_MONTH = CloseYm.of("202610");

    private DryRunScenario() {
    }

    static OutboxEngine engine(OraclePersistence p) {
        return new OutboxEngine(RuleFixtures.standardRules(), CalcTestHarness.standardDirectory(),
                new OutboxEngine.Raw(p.policyEventStore(), p.commCalcStore(), p.limitLedgerStore(), p.deferralScheduleStore(),
                        p.clawbackReceivableStore(), p.adjustmentStore(), p.settleCloseStore(), p.agentSettlementStore(),
                        p.payoutStatementStore(), p.outboxStore()),
                Clock.fixed(Instant.parse("2026-11-01T17:10:00Z"), ZoneId.of("Asia/Seoul")), UUID::randomUUID);
    }

    static void seed(OraclePersistence p, OutboxEngine e) {
        p.inTx(() -> e.calculator.process(EventFixtures.newContract()));
        p.inTx(() -> e.closeService.close(CloseYm.of("202608"), "정산담당"));
        p.inTx(() -> e.payoutService.run(CloseYm.of("202608"), 1));
        p.inTx(() -> e.calculator.process(EventFixtures.terminal(EventType.CANCEL, EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE,
                LocalDate.of(2026, 9, 15), 2)));
        p.inTx(() -> e.closeService.close(CloseYm.of("202609"), "정산담당"));
        p.inTx(() -> e.payoutService.run(CloseYm.of("202609"), 1));
        p.inTx(() -> e.calculator.process(EventFixtures.newContract(new PolicyNo("POL-2026-0002"), LocalDate.of(2026, 10, 1),
                Money.won(300_000), Map.of())));
        p.inTx(() -> e.calculator.process(EventFixtures.newContract(new PolicyNo("POL-2027-0001"), LocalDate.of(2027, 1, 15),
                Money.won(300_000), Map.of())));
        p.inTx(() -> e.raw.outbox().assignPending(10_000));   // P13: 부여를 먼저 끝낸 뒤 스냅샷
    }

    /** 시뮬레이터(E2 ②) — 엔진 저장소의 읽기 메서드 참조만(앱 {@code SimulationConfig}과 같은 배선, 원장은 잠그지 않는 {@code peek}). */
    static DryRunSimulator simulator(OutboxEngine e) {
        return e.simulator(new InMemoryIncentiveStore(), "A-1001"::equals);
    }

    /** ② 두 엔드포인트를 한 번씩 이상 — 원장이 있는 계약(해약·감액), 분급이 있는 계약(해약 — 취소 예정 스케줄), 신계약. */
    static List<Object> simulateAll(DryRunSimulator sim) {
        List<Object> out = new ArrayList<>();
        out.add(sim.simulateNewContract(new SimulationModel.NewContractRequest("A-1001", RuleFixtures.PRODUCT.value(), 300_000,
                LocalDate.of(2026, 10, 5), null, null, null)));
        out.add(sim.simulatePolicyEvent(new SimulationModel.PolicyEventRequest("A-1001", "POL-2026-0002", "CANCEL",
                LocalDate.of(2026, 10, 20), 1, null)));
        out.add(sim.simulatePolicyEvent(new SimulationModel.PolicyEventRequest("A-1001", "POL-2026-0002", "REDUCE",
                LocalDate.of(2026, 10, 20), null, 100_000L)));
        out.add(sim.simulatePolicyEvent(new SimulationModel.PolicyEventRequest("A-1001", "POL-2027-0001", "CANCEL",
                LocalDate.of(2027, 3, 10), 2, null)));
        return out;
    }

    /** 명세 조회(③) — {@code StatementQueryOracleIT}와 같은 배선. */
    static StatementQuery statementQuery(OraclePersistence p, OutboxEngine e) {
        EventFeed.Tx tx = new EventFeed.Tx() {
            @Override
            public <T> T run(java.util.function.Supplier<T> work) {
                return p.inTx(work);
            }
        };
        AgentMasterReader agents = new InMemoryAgentMasterReader()
                .with(new AgentMasterReader.AgentMaster("A-1001", "허구설계사", LocalDate.of(2020, 1, 1), null));
        return new StatementQuery(e.estimator, e.calcs, e.stores.adjustments(), p.settleCloseStore(), p.payoutStatementStore(), agents,
                p.outboxStore(), tx, WithholdingTaxPolicy.STANDARD_3_3, Clock.fixed(Instant.parse("2026-11-01T17:10:00Z"), ZoneId.of("Asia/Seoul")));
    }

    /** 표마다 전 행(전 컬럼)의 정렬된 문자열 해시. */
    static Map<String, String> hashes(JdbcTemplate jdbc) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String t : BUSINESS_TABLES) {
            out.put(t, hashOf(rows(jdbc, "SELECT * FROM " + t)));
        }
        return out;
    }

    static List<String> rows(JdbcTemplate jdbc, String sql) {
        List<String> rows = new ArrayList<>(jdbc.query(sql, (rs, n) -> {
            StringBuilder b = new StringBuilder();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                b.append(rs.getMetaData().getColumnName(i)).append('=').append(rs.getString(i)).append('|');
            }
            return b.toString();
        }));
        rows.sort(null);
        return rows;
    }

    static String hashOf(List<String> rows) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (String r : rows) {
                d.update(r.getBytes(StandardCharsets.UTF_8));
                d.update((byte) '\n');
            }
            return HexFormat.of().formatHex(d.digest()).substring(0, 16) + " (" + rows.size() + "행)";
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
