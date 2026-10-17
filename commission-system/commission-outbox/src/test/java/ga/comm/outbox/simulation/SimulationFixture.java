package ga.comm.outbox.simulation;

import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.InMemoryCommCalcStore;
import ga.comm.calc.fixture.InMemoryPolicyEventStore;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.clawback.fixture.InMemoryClawbackReceivableStore;
import ga.comm.deferral.fixture.InMemoryDeferralScheduleStore;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.domain.type.EventType;
import ga.comm.limit.LimitLedger;
import ga.comm.limit.fixture.InMemoryLimitLedgerStore;
import ga.comm.outbox.fixture.InMemoryOutboxStore;
import ga.comm.outbox.fixture.OutboxEngine;
import ga.comm.rule.IncentiveRepository;
import ga.comm.rule.RuleRepository;
import ga.comm.rule.fixture.InMemoryIncentiveStore;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.IncentiveRule;
import ga.comm.rule.model.PayoutKind;
import ga.comm.rule.model.RateStatus;
import ga.comm.settlement.fixture.InMemoryAdjustmentStore;
import ga.comm.settlement.fixture.InMemoryAgentSettlementStore;
import ga.comm.settlement.fixture.InMemoryPayoutStatementStore;
import ga.comm.settlement.fixture.InMemorySettleCloseStore;
import ga.comm.settlement.simulation.DryRunSimulator;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 시뮬레이션 시험의 인메모리 엔진(앱 조립과 같은 시뮬레이터 배선 — {@link OutboxEngine#simulator}). */
final class SimulationFixture {

    static final String AGENT = "A-1001";
    static final InsurerCode INSURER = RuleFixtures.INSURER;
    static final ProductKey PRODUCT = RuleFixtures.PRODUCT;

    private SimulationFixture() {
    }

    static OutboxEngine engine(RuleRepository rules, Clock clock) {
        return new OutboxEngine(rules, CalcTestHarness.standardDirectory(),
                new OutboxEngine.Raw(new InMemoryPolicyEventStore(), new InMemoryCommCalcStore(), new InMemoryLimitLedgerStore(),
                        new InMemoryDeferralScheduleStore(), new InMemoryClawbackReceivableStore(), new InMemoryAdjustmentStore(),
                        new InMemorySettleCloseStore(), new InMemoryAgentSettlementStore(), new InMemoryPayoutStatementStore(),
                        new InMemoryOutboxStore()),
                clock, OutboxEngine.sequentialIds());
    }

    static DryRunSimulator simulator(OutboxEngine e, IncentiveRepository incentives) {
        return e.simulator(incentives, AGENT::equals);
    }

    static IncentiveRepository noIncentives() {
        return new InMemoryIncentiveStore();
    }

    /** 신계약 정액 시책(조건 eventType == 'NEW') 코드별. */
    static InMemoryIncentiveStore fixedNewIncentives(Map<String, Long> amountByCode) {
        InMemoryIncentiveStore store = new InMemoryIncentiveStore();
        amountByCode.forEach((code, amount) -> store.insert(new IncentiveRule(store.nextIncentiveId(), code, INSURER, PRODUCT, null,
                "eventType == 'NEW'", PayoutKind.FIXED, Money.won(amount), (Rate) null, EffectivePeriod.from(LocalDate.of(2026, 1, 1)),
                1L, RateStatus.ACTIVE)));
        return store;
    }

    static PolicyEvent newContract(String policyNo, LocalDate contractDate, long premium, Map<String, String> attributes) {
        return new PolicyEvent(null, INSURER.value() + ":" + policyNo + ":NEW", new PolicyNo(policyNo), INSURER, PRODUCT, EventType.NEW,
                contractDate, contractDate, new AgentId(AGENT), Money.won(premium), null, null, attributes);
    }

    static PolicyEvent payment(String policyNo, LocalDate contractDate, long premium, int no) {
        return new PolicyEvent(null, INSURER.value() + ":" + policyNo + ":PAYMENT:" + no, new PolicyNo(policyNo), INSURER, PRODUCT,
                EventType.PAYMENT, contractDate.plusMonths(no - 1L), contractDate, new AgentId(AGENT), Money.won(premium), null, no, Map.of());
    }

    static PolicyEvent terminal(EventType type, String policyNo, LocalDate contractDate, long premium, LocalDate on, Integer elapsed) {
        return new PolicyEvent(null, INSURER.value() + ":" + policyNo + ":" + type, new PolicyNo(policyNo), INSURER, PRODUCT, type, on,
                contractDate, new AgentId(AGENT), Money.won(premium), null, elapsed, Map.of());
    }

    /** 업무 저장소의 상태 지문(엔진 원 저장소 — 시뮬레이션 전후 비교, 인메모리판 C1). */
    static List<String> fingerprint(OutboxEngine e) {
        List<String> out = new ArrayList<>();
        for (CommCalcRecord r : ((InMemoryCommCalcStore) e.raw.calcs()).all()) {
            out.add("calc " + r);
        }
        for (LimitLedger l : e.raw.ledgers().findAll()) {
            out.add("ledger " + l.policyNo() + "/" + l.agentId() + " limit=" + l.limitAmount() + " accum=" + l.accumPaid()
                    + " premium=" + l.monthlyPremium() + " postings=" + l.postings());
        }
        ((InMemoryDeferralScheduleStore) e.raw.schedules()).all().forEach(s -> out.add("schedule " + s));
        out.add("events unprocessed=" + e.raw.events().unprocessedCount());
        ((InMemoryOutboxStore) e.raw.outbox()).all().forEach(s -> out.add("outbox " + s));
        return out;
    }
}
