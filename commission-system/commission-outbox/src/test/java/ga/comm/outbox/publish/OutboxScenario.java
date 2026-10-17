package ga.comm.outbox.publish;

import com.fasterxml.jackson.databind.JsonNode;
import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.InMemoryCommCalcStore;
import ga.comm.calc.fixture.InMemoryPolicyEventStore;
import ga.comm.calc.revision.RevisionService;
import ga.comm.clawback.fixture.InMemoryClawbackReceivableStore;
import ga.comm.deferral.DeferralReleaseService;
import ga.comm.deferral.fixture.InMemoryDeferralScheduleStore;
import ga.comm.limit.fixture.InMemoryLimitLedgerStore;
import ga.comm.outbox.fixture.InMemoryOutboxStore;
import ga.comm.outbox.fixture.OutboxEngine;
import ga.comm.rule.fixture.InMemoryRuleStore;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.settlement.AdjustmentService;
import ga.comm.settlement.MonthCloseService;
import ga.comm.settlement.PayoutService;
import ga.comm.settlement.fixture.InMemoryAdjustmentStore;
import ga.comm.settlement.fixture.InMemoryAgentSettlementStore;
import ga.comm.settlement.fixture.InMemoryPayoutStatementStore;
import ga.comm.settlement.fixture.InMemorySettleCloseStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/** 인메모리 {@link OutboxEngine} — 시험이 원본 저장소를 직접 읽는다. 시계·eventId는 결정론. */
final class OutboxScenario {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-05T09:00:00Z"), ZoneId.of("Asia/Seoul"));

    final InMemoryPolicyEventStore rawEvents = new InMemoryPolicyEventStore();
    final InMemoryCommCalcStore rawCalcs = new InMemoryCommCalcStore();
    final InMemoryLimitLedgerStore rawLedgers = new InMemoryLimitLedgerStore();
    final InMemoryDeferralScheduleStore rawSchedules = new InMemoryDeferralScheduleStore();
    final InMemoryClawbackReceivableStore rawReceivables = new InMemoryClawbackReceivableStore();
    final InMemoryAdjustmentStore rawAdjustments = new InMemoryAdjustmentStore();
    final InMemorySettleCloseStore closeStore = new InMemorySettleCloseStore();
    final InMemoryAgentSettlementStore settlementStore = new InMemoryAgentSettlementStore();
    final InMemoryPayoutStatementStore statementStore = new InMemoryPayoutStatementStore();
    final InMemoryOutboxStore outbox = new InMemoryOutboxStore();

    final OutboxEngine engine;
    final PublishingStores stores;
    final CommissionCalculator calculator;
    final RevisionService revision;
    final DeferralReleaseService releaseService;
    final MonthCloseService closeService;
    final PayoutService payoutService;
    final AdjustmentService adjustmentService;

    OutboxScenario() {
        this(RuleFixtures.standardRules());
    }

    OutboxScenario(InMemoryRuleStore rules) {
        engine = new OutboxEngine(rules, CalcTestHarness.standardDirectory(),
                new OutboxEngine.Raw(rawEvents, rawCalcs, rawLedgers, rawSchedules, rawReceivables, rawAdjustments, closeStore,
                        settlementStore, statementStore, outbox),
                CLOCK, OutboxEngine.sequentialIds());
        stores = engine.stores;
        calculator = engine.calculator;
        revision = engine.revision;
        releaseService = engine.releaseService;
        closeService = engine.closeService;
        payoutService = engine.payoutService;
        adjustmentService = engine.adjustmentService;
    }

    List<JsonNode> events() {
        return engine.eventsAfter(0);
    }

    List<JsonNode> events(String type) {
        return events().stream().filter(e -> type.equals(e.path("type").asText())).toList();
    }

    List<JsonNode> payloads(String type) {
        return events(type).stream().map(e -> e.path("payload")).toList();
    }
}
