package ga.comm.outbox.publish;

import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.calc.fixture.InMemoryCommCalcStore;
import ga.comm.calc.fixture.InMemoryPolicyEventStore;
import ga.comm.domain.type.CalcStatus;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.clawback.fixture.InMemoryClawbackReceivableStore;
import ga.comm.deferral.ScheduleEntry;
import ga.comm.deferral.ScheduleStatus;
import ga.comm.deferral.fixture.InMemoryDeferralScheduleStore;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.type.RecipientType;
import ga.comm.limit.fixture.InMemoryLimitLedgerStore;
import ga.comm.outbox.fixture.InMemoryOutboxStore;
import ga.comm.outbox.fixture.OutboxEngine;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.settlement.ClosedMonthGuardedCalcStore;
import ga.comm.settlement.fixture.InMemoryAdjustmentStore;
import ga.comm.settlement.fixture.InMemoryAgentSettlementStore;
import ga.comm.settlement.fixture.InMemoryPayoutStatementStore;
import ga.comm.settlement.fixture.InMemorySettleCloseStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E2 ④·C7 — 마감월 가드가 발행 데코레이터 바깥에 있다(운영과 같은 조립 — {@link OutboxEngine}). 해석기를 우회해 CLOSED 월로 넣는 insert는
 * 거부되고 아웃박스에 아무것도 남지 않는다. 가드가 막는 유일한 기존 경로(E2 P10): 이미 CLOSED인 달을 준 분급 도래 처리.
 */
class ClosedMonthGuardAssemblyTest {

    private final InMemoryOutboxStore outbox = new InMemoryOutboxStore();
    private final InMemoryCommCalcStore rawCalcs = new InMemoryCommCalcStore();
    private final InMemoryDeferralScheduleStore rawSchedules = new InMemoryDeferralScheduleStore();
    private final OutboxEngine e = new OutboxEngine(RuleFixtures.standardRules(), CalcTestHarness.standardDirectory(),
            new OutboxEngine.Raw(new InMemoryPolicyEventStore(), rawCalcs, new InMemoryLimitLedgerStore(), rawSchedules,
                    new InMemoryClawbackReceivableStore(), new InMemoryAdjustmentStore(), new InMemorySettleCloseStore(),
                    new InMemoryAgentSettlementStore(), new InMemoryPayoutStatementStore(), outbox),
            Clock.fixed(Instant.parse("2026-09-05T09:00:00Z"), ZoneId.of("Asia/Seoul")), OutboxEngine.sequentialIds());

    private static final CloseYm AUG = CloseYm.of("202608");

    @Test
    void 엔진의_계산_저장소는_가드가_바깥이다() {
        assertThat(e.calcs).isInstanceOf(ClosedMonthGuardedCalcStore.class);
    }

    @Test
    void 해석기를_우회한_마감월_insert는_거부되고_발행되지_않는다() {
        e.calculator.process(EventFixtures.newContract());
        e.closeService.close(AUG, "정산담당");
        int before = outbox.all().size();
        int calcsBefore = rawCalcs.findByCloseYm(AUG).size();

        CommCalcRecord intoClosed = new CommCalcRecord(null, 1L, EventFixtures.POLICY_1, RecipientType.AGENT, "A-1001",
                CommTypeCode.DEFERRED, Money.won(10_000), null, Money.won(10_000), Money.ZERO, AUG, CalcStatus.CALCULATED, null, "[]", "[]");
        assertThatThrownBy(() -> e.calcs.insert(intoClosed)).isInstanceOf(IllegalStateException.class).hasMessageContaining("마감월");
        assertThat(outbox.all().size()).as("거부된 insert는 아웃박스에 닿지 않는다").isEqualTo(before);
        assertThat(rawCalcs.findByCloseYm(AUG)).hasSize(calcsBefore);
    }

    /** P10 — 마감 훅이 도래분을 이미 풀었으므로 실제로는 드물다. 마감 뒤 SCHEDULED로 남은 도래분을 CLOSED 월로 풀면 거부(결함 수정). */
    @Test
    void 마감된_달의_분급_도래_처리는_거부된다() {
        e.calculator.process(EventFixtures.newContract());
        e.closeService.close(AUG, "정산담당");
        long source = rawCalcs.findByCloseYm(AUG).getFirst().calcId();
        rawSchedules.createAll(List.of(new ScheduleEntry(null, source, EventFixtures.POLICY_1, EventFixtures.AGENT_A, AUG, Money.won(10_000),
                ScheduleEntry.CONDITION_POLICY_INFORCE, ScheduleStatus.SCHEDULED, 1L, null)));
        int before = outbox.all().size();

        assertThatThrownBy(() -> e.releaseService.release(AUG)).isInstanceOf(IllegalStateException.class).hasMessageContaining("마감월");
        assertThat(outbox.all().size()).isEqualTo(before);
    }
}
