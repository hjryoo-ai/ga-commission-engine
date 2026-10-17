package ga.comm.outbox.simulation;

import ga.comm.calc.store.CommCalcRecord;
import ga.comm.deferral.ScheduleEntry;
import ga.comm.deferral.ScheduleStatus;
import ga.comm.deferral.fixture.InMemoryDeferralScheduleStore;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Rate;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.Direction;
import ga.comm.domain.type.EventType;
import ga.comm.domain.type.RecipientType;
import ga.comm.limit.LimitLedger;
import ga.comm.outbox.fixture.OutboxEngine;
import ga.comm.outbox.fixture.SteppingClock;
import ga.comm.rule.fixture.InMemoryRuleStore;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.rule.model.CommRateRule;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.RateStatus;
import ga.comm.settlement.simulation.DryRunSimulator;
import ga.comm.settlement.simulation.SimulationModel.DueSlice;
import ga.comm.settlement.simulation.SimulationModel.Incentive;
import ga.comm.settlement.simulation.SimulationModel.Installment;
import ga.comm.settlement.simulation.SimulationModel.NewContractRequest;
import ga.comm.settlement.simulation.SimulationModel.NewContractResult;
import ga.comm.settlement.simulation.SimulationModel.PolicyEventRequest;
import ga.comm.settlement.simulation.SimulationModel.PolicyEventResult;
import ga.comm.settlement.simulation.SimulationRejected;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static ga.comm.outbox.simulation.SimulationFixture.AGENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E2 ② — 시뮬레이터(오버레이 파이프라인). 신계약은 같은 가상 이벤트를 <b>실제로 처리한 결과</b>와 금액이 같고, 계약 이벤트는 같은 이벤트를 실제로
 * 처리한 뒤의 환수 레코드·귀속월 순액·취소 스케줄과 같다. 어느 경우든 엔진 원 저장소·아웃박스의 지문이 전후 같다(인메모리판 C1 — 인메모리 원장은
 * 같은 인스턴스를 주므로 오버레이가 사본을 만들지 않으면 여기서 드러난다). Oracle판 C1·C3은 {@code DryRunNoWriteOracleIT}·{@code DryRunLockOracleIT}.
 */
class DryRunSimulatorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-20T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDate AUG1 = LocalDate.of(2026, 8, 1);

    private static NewContractRequest req(LocalDate contractDate, Integer term, String insurer, List<String> codes) {
        return new NewContractRequest(AGENT, RuleFixtures.PRODUCT.value(), 150_000, contractDate, term, insurer, codes);
    }

    /** 실제 처리: 같은 가상 이벤트(NEW + PAYMENT 2..n)를 다른 엔진에서 저장까지. 회차별 AGENT FY_COMM 합. */
    private static List<Installment> actualFy(OutboxEngine e, LocalDate contractDate, int n) {
        List<Installment> out = new ArrayList<>();
        for (int no = 1; no <= n; no++) {
            var event = no == 1
                    ? SimulationFixture.newContract(DryRunSimulator.SIM_POLICY_NO, contractDate, 150_000, Map.of())
                    : SimulationFixture.payment(DryRunSimulator.SIM_POLICY_NO, contractDate, 150_000, no);
            long sum = e.calculator.process(event).stream()
                    .filter(r -> r.recipientType() == RecipientType.AGENT && r.commType().equals(CommTypeCode.FY_COMM))
                    .mapToLong(r -> r.calcAmount().toLong()).sum();
            out.add(new Installment(no, sum));
        }
        return out;
    }

    @Test
    void 신계약은_같은_이벤트를_실제로_처리한_금액과_같고_저장소에_아무것도_남기지_않는다() {
        OutboxEngine e = SimulationFixture.engine(RuleFixtures.standardRules(), CLOCK);
        List<String> before = SimulationFixture.fingerprint(e);

        NewContractResult r = SimulationFixture.simulator(e, SimulationFixture.noIncentives()).simulateNewContract(req(AUG1, null, null, null));

        assertThat(SimulationFixture.fingerprint(e)).as("원 저장소·아웃박스 불변").isEqualTo(before);
        assertThat(r.fyCommissionByInstallment()).as("FY 요율이 있는 회차까지(1~12)").hasSize(12);
        assertThat(r.fyCommissionByInstallment().getFirst()).isEqualTo(new Installment(1, 945_000));
        assertThat(r.fyCommissionByInstallment().get(1)).isEqualTo(new Installment(2, 20_250));

        OutboxEngine actual = SimulationFixture.engine(RuleFixtures.standardRules(), CLOCK);
        assertThat(r.fyCommissionByInstallment()).isEqualTo(actualFy(actual, AUG1, 12));
        LimitLedger ledger = actual.raw.ledgers().find(new PolicyNo(DryRunSimulator.SIM_POLICY_NO), new AgentId(AGENT)).orElseThrow();
        assertThat(r.limitAmount()).isEqualTo(ledger.limitAmount().toLong()).isEqualTo(1_800_000L);
        assertThat(r.projectedAccum()).isEqualTo(ledger.accumPaid().toLong()).isEqualTo(1_167_750L);
        assertThat(r.incentives()).isEmpty();
        assertThat(r.deferral().schedule()).as("2027 이전 체결 — 분급 없음").isEmpty();
        assertThat(r.deferral().firstYearPayout()).isEqualTo(1_167_750L);
        assertThat(r.generatedAt()).isEqualTo("2026-07-20T10:00:00+09:00");
    }

    @Test
    void 납입기간이_회차를_자른다() {
        OutboxEngine e = SimulationFixture.engine(RuleFixtures.standardRules(), CLOCK);
        NewContractResult r = SimulationFixture.simulator(e, SimulationFixture.noIncentives()).simulateNewContract(req(AUG1, 5, null, null));
        assertThat(r.fyCommissionByInstallment()).extracting(Installment::no).containsExactly(1, 2, 3, 4, 5);
        assertThat(r.projectedAccum()).isEqualTo(945_000L + 4 * 20_250L);
    }

    @Test
    void 한도_초과_시책은_삭감_뒤_금액과_삭감액이고_코드를_주면_그_시책만() {
        OutboxEngine e = SimulationFixture.engine(RuleFixtures.standardRules(), CLOCK);
        var incentives = SimulationFixture.fixedNewIncentives(Map.of("Q4-NEW", 1_000_000L, "Q4-BONUS", 50_000L));
        DryRunSimulator sim = SimulationFixture.simulator(e, incentives);

        NewContractResult only = sim.simulateNewContract(req(AUG1, null, "SAMLIFE", List.of("Q4-NEW")));
        // A-1과 같은 숫자: 한도 1,800,000 = FY 945,000 + 시책 855,000(삭감 145,000) — 그 뒤 회차의 FY는 전액 삭감
        assertThat(only.incentives()).containsExactly(new Incentive("Q4-NEW", 855_000, 145_000, true));
        assertThat(only.fyCommissionByInstallment().getFirst().amount()).isEqualTo(945_000);
        assertThat(only.fyCommissionByInstallment().subList(1, 12)).allMatch(i -> i.amount() == 0);
        assertThat(only.projectedAccum()).isEqualTo(1_800_000L).isEqualTo(only.limitAmount());
        assertThat(only.deferral().firstYearPayout()).isEqualTo(1_800_000L);

        NewContractResult none = sim.simulateNewContract(req(AUG1, null, null, List.of("NO-SUCH")));
        assertThat(none.incentives()).isEmpty();
        assertThat(none.projectedAccum()).isEqualTo(1_167_750L);

        NewContractResult both = sim.simulateNewContract(req(AUG1, null, null, null));
        assertThat(both.incentives()).extracting(Incentive::code).containsExactlyInAnyOrder("Q4-NEW", "Q4-BONUS");
        assertThat(both.incentives().stream().mapToLong(Incentive::amount).sum() + 945_000).isEqualTo(1_800_000L);
    }

    @Test
    void 체결일_2026_06_30은_한도가_없다() {
        OutboxEngine e = SimulationFixture.engine(RuleFixtures.standardRules(), CLOCK);
        NewContractResult r = SimulationFixture.simulator(e, SimulationFixture.noIncentives())
                .simulateNewContract(req(LocalDate.of(2026, 6, 30), null, null, null));
        assertThat(r.limitAmount()).isNull();
        assertThat(r.projectedAccum()).isNull();
        assertThat(r.fyCommissionByInstallment().getFirst().amount()).isEqualTo(945_000);
    }

    @Test
    void 분급_커브가_있으면_스케줄은_실제_처리의_스케줄과_같다() {
        LocalDate jan15 = LocalDate.of(2027, 1, 15);
        OutboxEngine e = SimulationFixture.engine(RuleFixtures.standardRules(), CLOCK);
        List<String> before = SimulationFixture.fingerprint(e);
        NewContractResult r = SimulationFixture.simulator(e, SimulationFixture.noIncentives()).simulateNewContract(req(jan15, null, null, null));
        assertThat(SimulationFixture.fingerprint(e)).isEqualTo(before);

        OutboxEngine actual = SimulationFixture.engine(RuleFixtures.standardRules(), CLOCK);
        assertThat(r.fyCommissionByInstallment()).isEqualTo(actualFy(actual, jan15, 12));
        assertThat(r.fyCommissionByInstallment().getFirst().amount()).as("즉시분 40%").isEqualTo(378_000);
        TreeMap<CloseYm, Long> byDue = new TreeMap<>();
        for (ScheduleEntry s : ((InMemoryDeferralScheduleStore) actual.raw.schedules()).all()) {
            byDue.merge(s.dueYm(), s.amount().toLong(), Long::sum);
        }
        List<DueSlice> expected = new ArrayList<>();
        byDue.forEach((ym, amount) -> expected.add(new DueSlice(ym, amount)));
        assertThat(r.deferral().schedule()).isNotEmpty().isEqualTo(expected);
        assertThat(r.deferral().firstYearPayout()).isEqualTo(r.fyCommissionByInstallment().stream().mapToLong(Installment::amount).sum());
        assertThat(r.projectedAccum()).as("한도는 분할 전 총액으로 소진").isEqualTo(1_167_750L);
    }

    @Test
    void 보험사가_없으면_요율이_한_보험사뿐일_때만_정하고_아니면_INSURER_AMBIGUOUS() {
        InMemoryRuleStore rules = RuleFixtures.standardRules();
        rules.addActiveRate(new CommRateRule(900, Direction.OUTBOUND, new InsurerCode("KBLIFE"), RuleFixtures.PRODUCT,
                CommTypeCode.FY_COMM, null, Rate.of("6.0"), EffectivePeriod.from(LocalDate.of(2026, 1, 1)), 1, RateStatus.ACTIVE));
        DryRunSimulator sim = SimulationFixture.simulator(SimulationFixture.engine(rules, CLOCK), SimulationFixture.noIncentives());

        assertThatThrownBy(() -> sim.simulateNewContract(req(AUG1, null, null, null)))
                .isInstanceOfSatisfying(SimulationRejected.class, x -> assertThat(x.kind()).isEqualTo(SimulationRejected.Kind.INSURER_AMBIGUOUS));
        assertThat(sim.simulateNewContract(req(AUG1, null, "SAMLIFE", null)).fyCommissionByInstallment().getFirst().amount())
                .isEqualTo(945_000);
    }

    @Test
    void 엔진에_없는_설계사는_AGENT_NOT_FOUND() {
        DryRunSimulator sim = SimulationFixture.simulator(SimulationFixture.engine(RuleFixtures.standardRules(), CLOCK),
                SimulationFixture.noIncentives());
        assertThatThrownBy(() -> sim.simulateNewContract(new NewContractRequest("A-9999", "WHOLE-LIFE-20Y", 150_000, AUG1, null, null, null)))
                .isInstanceOfSatisfying(SimulationRejected.class, x -> assertThat(x.kind()).isEqualTo(SimulationRejected.Kind.AGENT_NOT_FOUND));
        assertThatThrownBy(() -> sim.simulatePolicyEvent(new PolicyEventRequest("A-9999", "POL-1", "CANCEL", AUG1, null, null)))
                .isInstanceOfSatisfying(SimulationRejected.class, x -> assertThat(x.kind()).isEqualTo(SimulationRejected.Kind.AGENT_NOT_FOUND));
    }

    // ---- policy-event ----

    /** A-1 첫 판의 해약 전까지: 신계약(V1 시책 1,000,000) → 202608 마감 → 지급. */
    private static OutboxEngine a1BeforeCancel(SteppingClock clock) {
        OutboxEngine e = SimulationFixture.engine(RuleFixtures.standardRules(), clock);
        e.calculator.process(SimulationFixture.newContract("POL-2026-0001", AUG1, 150_000, Map.of("incentive_amount", "1000000")));
        clock.set(OffsetDateTime.parse("2026-09-05T18:00:00+09:00"));
        e.closeService.close(CloseYm.of("202608"), "정산담당");
        clock.set(OffsetDateTime.parse("2026-09-10T10:00:00+09:00"));
        e.payoutService.run(CloseYm.of("202608"), 1);
        clock.set(OffsetDateTime.parse("2027-02-15T09:30:00+09:00"));
        return e;
    }

    private static SteppingClock clock() {
        return new SteppingClock(Instant.parse("2026-08-01T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    }

    @Test
    void 해약은_실제로_처리한_뒤의_환수_레코드와_귀속월_순액과_같다() {
        SteppingClock clock = clock();
        OutboxEngine e = a1BeforeCancel(clock);
        List<String> before = SimulationFixture.fingerprint(e);
        DryRunSimulator sim = SimulationFixture.simulator(e, SimulationFixture.noIncentives());

        PolicyEventResult r = sim.simulatePolicyEvent(new PolicyEventRequest(AGENT, "POL-2026-0001", "CANCEL", LocalDate.of(2027, 2, 15), 7, null));
        PolicyEventResult six = sim.simulatePolicyEvent(new PolicyEventRequest(AGENT, "POL-2026-0001", "CANCEL", LocalDate.of(2027, 2, 15), 6, null));
        assertThat(SimulationFixture.fingerprint(e)).as("원 저장소·아웃박스 불변").isEqualTo(before);

        // 6/7회차 경계(R4 elapsedInstallments): 7회차 40%, 6회차 70% — 순 기지급 1,800,000
        assertThat(r.clawbackAmount()).isEqualTo(-720_000);
        assertThat(six.clawbackAmount()).isEqualTo(-1_260_000);
        assertThat(r.offsetAgainstMonth().closeYm()).isEqualTo(CloseYm.of("202702"));
        assertThat(r.offsetAgainstMonth().before()).isZero();
        assertThat(r.receivableRemaining()).as("열린 채권 0 + 음수 순액 720,000").isEqualTo(720_000);
        assertThat(r.cancelledDeferrals()).isEmpty();

        // 실제 처리
        List<CommCalcRecord> saved = e.calculator.process(SimulationFixture.terminal(EventType.CANCEL, "POL-2026-0001", AUG1, 150_000,
                LocalDate.of(2027, 2, 15), 7));
        long actualClawback = saved.stream().filter(x -> x.recipientType() == RecipientType.AGENT && x.commType().equals(CommTypeCode.CLAWBACK))
                .mapToLong(x -> x.calcAmount().toLong()).sum();
        assertThat(r.clawbackAmount()).isEqualTo(actualClawback);
        assertThat(r.offsetAgainstMonth().after())
                .isEqualTo(e.estimator.estimate(CloseYm.of("202702"), AGENT).orElseThrow().math().grossNet().toLong());
    }

    @Test
    void 해약은_미도래_분급을_취소_예정으로_보이고_원_스케줄은_그대로다() {
        LocalDate jan15 = LocalDate.of(2027, 1, 15);
        OutboxEngine e = SimulationFixture.engine(RuleFixtures.standardRules(), CLOCK);
        e.calculator.process(SimulationFixture.newContract("POL-2027-0001", jan15, 150_000, Map.of()));
        InMemoryDeferralScheduleStore raw = (InMemoryDeferralScheduleStore) e.raw.schedules();
        assertThat(raw.all()).hasSize(3);
        List<String> before = SimulationFixture.fingerprint(e);

        PolicyEventResult r = SimulationFixture.simulator(e, SimulationFixture.noIncentives())
                .simulatePolicyEvent(new PolicyEventRequest(AGENT, "POL-2027-0001", "CANCEL", LocalDate.of(2027, 4, 10), 3, null));
        assertThat(SimulationFixture.fingerprint(e)).isEqualTo(before);
        assertThat(raw.all()).allMatch(s -> s.status() == ScheduleStatus.SCHEDULED);

        e.calculator.process(SimulationFixture.terminal(EventType.CANCEL, "POL-2027-0001", jan15, 150_000, LocalDate.of(2027, 4, 10), 3));
        assertThat(r.cancelledDeferrals()).extracting(c -> c.scheduleId() + "/" + c.dueYm() + "/" + c.amount())
                .containsExactlyElementsOf(raw.all().stream().filter(s -> s.status() == ScheduleStatus.CANCELLED)
                        .map(s -> s.scheduleId() + "/" + s.dueYm() + "/" + s.amount().toLong()).toList());
        assertThat(r.cancelledDeferrals()).hasSize(3);
    }

    @Test
    void 부활은_REVIVE로_실제_처리와_같은_재지급이고_감액은_원장을_바꾸지_않는다() {
        SteppingClock clock = clock();
        OutboxEngine e = a1BeforeCancel(clock);
        e.calculator.process(SimulationFixture.terminal(EventType.CANCEL, "POL-2026-0001", AUG1, 150_000, LocalDate.of(2027, 2, 15), 7));
        DryRunSimulator sim = SimulationFixture.simulator(e, SimulationFixture.noIncentives());

        PolicyEventResult revive = sim.simulatePolicyEvent(new PolicyEventRequest(AGENT, "POL-2026-0001", "REINSTATE", LocalDate.of(2027, 3, 2), null, null));
        long ledgerLimit = e.raw.ledgers().find(new PolicyNo("POL-2026-0001"), new AgentId(AGENT)).orElseThrow().limitAmount().toLong();
        PolicyEventResult reduce = sim.simulatePolicyEvent(new PolicyEventRequest(AGENT, "POL-2026-0001", "REDUCE", LocalDate.of(2027, 3, 2), null, 100_000L));
        assertThat(e.raw.ledgers().find(new PolicyNo("POL-2026-0001"), new AgentId(AGENT)).orElseThrow().limitAmount().toLong())
                .as("재산정은 사본에만").isEqualTo(ledgerLimit);
        assertThat(reduce.clawbackAmount()).isZero();

        List<CommCalcRecord> saved = e.calculator.process(SimulationFixture.terminal(EventType.REVIVE, "POL-2026-0001", AUG1, 150_000,
                LocalDate.of(2027, 3, 2), null));
        long actual = saved.stream().filter(x -> x.recipientType() == RecipientType.AGENT && x.commType().equals(CommTypeCode.CLAWBACK))
                .mapToLong(x -> x.calcAmount().toLong()).sum();
        assertThat(revive.clawbackAmount()).isEqualTo(actual).isEqualTo(720_000);
    }

    @Test
    void 계약_이벤트의_거부_코드() {
        SteppingClock clock = clock();
        DryRunSimulator sim = SimulationFixture.simulator(a1BeforeCancel(clock), SimulationFixture.noIncentives());
        LocalDate d = LocalDate.of(2027, 2, 15);
        assertThatThrownBy(() -> sim.simulatePolicyEvent(new PolicyEventRequest(AGENT, "POL-9999", "CANCEL", d, 7, null)))
                .isInstanceOfSatisfying(SimulationRejected.class, x -> assertThat(x.kind()).isEqualTo(SimulationRejected.Kind.POLICY_NOT_FOUND));
        assertThatThrownBy(() -> sim.simulatePolicyEvent(new PolicyEventRequest(AGENT, "POL-2026-0001", "PAYMENT_OVERDUE", d, null, null)))
                .isInstanceOfSatisfying(SimulationRejected.class, x -> assertThat(x.kind()).isEqualTo(SimulationRejected.Kind.UNSUPPORTED_EVENT_TYPE));
        assertThatThrownBy(() -> sim.simulatePolicyEvent(new PolicyEventRequest(AGENT, "POL-2026-0001", "REDUCE", d, null, null)))
                .isInstanceOfSatisfying(SimulationRejected.class, x -> assertThat(x.kind()).isEqualTo(SimulationRejected.Kind.INVALID_REQUEST));
    }

    @Test
    void 같은_입력은_시계와_무관하게_같은_결과이고_generatedAt만_시계를_따른다() {
        SteppingClock clock = clock();
        OutboxEngine e = a1BeforeCancel(clock);
        DryRunSimulator sim = SimulationFixture.simulator(e, SimulationFixture.noIncentives());
        PolicyEventRequest cancel = new PolicyEventRequest(AGENT, "POL-2026-0001", "CANCEL", LocalDate.of(2027, 2, 15), 7, null);

        NewContractResult n1 = sim.simulateNewContract(req(AUG1, null, null, null));
        PolicyEventResult p1 = sim.simulatePolicyEvent(cancel);
        clock.set(OffsetDateTime.parse("2027-02-15T11:45:00+09:00"));
        NewContractResult n2 = sim.simulateNewContract(req(AUG1, null, null, null));
        PolicyEventResult p2 = sim.simulatePolicyEvent(cancel);

        assertThat(p2).isEqualTo(p1);
        assertThat(n2.ruleSnapshotId()).isEqualTo(n1.ruleSnapshotId()).startsWith("RS-").hasSize(35);
        assertThat(n2.generatedAt()).isEqualTo("2027-02-15T11:45:00+09:00").isNotEqualTo(n1.generatedAt());
        assertThat(new NewContractResult(n2.limitAmount(), n2.fyCommissionByInstallment(), n2.incentives(), n2.projectedAccum(),
                n2.deferral(), n2.ruleSnapshotId(), n1.generatedAt())).isEqualTo(n1);
        assertThat(sim.simulateNewContract(req(LocalDate.of(2027, 1, 15), null, null, null)).ruleSnapshotId())
                .as("분급 커브가 끼면 룰 묶음이 다르다").isNotEqualTo(n1.ruleSnapshotId());
    }
}
