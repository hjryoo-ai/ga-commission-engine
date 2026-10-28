package ga.comm.api.demo;

import ga.comm.calc.fixture.InMemoryAgentDirectory;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.deferral.PolicyStatusProvider;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.EventType;
import ga.comm.domain.type.RecipientType;
import ga.comm.outbox.fixture.OutboxEngine;
import ga.comm.outbox.fixture.SteppingClock;
import ga.comm.rule.fixture.InMemoryRuleStore;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.rule.model.ChannelType;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.LimitRule;
import ga.comm.rule.model.OverLimitAction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 포털 Phase 8 ⓪b — 데모 테넌트 "데모GA"의 엔진 스트림(포털 설계서 부록 B, 포털 계획 Q1·P-3·P-4). 엔진이 자기 파이프라인으로 낸다 — 포털은
 * 사본을 바이트로 들일 뿐 계산하지 않는다.
 *
 * <ul>
 *   <li>설계사 30명(본부 H1 · 지점 B1·B2 · 팀 T1~T6, 팀마다 다섯), 등급 SR·JR 번갈아.</li>
 *   <li>계약은 설계사마다 기본 14건(체결일 2026-03~2027-06 — 표준 룰의 요율 효력이 2026-01-01부터, P-3) + 필수 케이스 계약 12건.
 *       회차 입금 매월, 해약(약 5%/년)·감액(약 2%/년)은 결정론 규칙으로.</li>
 *   <li>매월 마감(익월 5일 18:00)·지급(익월 10일 10:00), 202603~202801. 202802는 열린 달 — 2028-03-01 02:10 잠정 명세. 24개월 창이
 *       2028-02까지인 것은 HELD 때문이다 — 분급 커브의 첫 이연분은 체결 12개월 뒤에 도래하고(2027-01 체결 → 202801), 엔진은
 *       DEFER_AFTER_FY에 이연 스케줄을 만들지 않는다(포털 계약 limitCutPolicy 설명문 — 엔진 §14).</li>
 *   <li>한도 규칙은 기간으로 나눈다(P-4): 2026-07-01~09-30 체결 CUT, 10-01부터 DEFER_AFTER_FY. 유지 판정은 HELD 계약 하나만
 *       202801부터 유지 아님(P-4 — 그 밖은 언제나 유지).</li>
 * </ul>
 * 금액은 전부 예시값이다.
 */
public final class DemoScenario {

    public static final String STREAM = "demo_ga";
    public static final String TENANT_NAME = "데모GA";
    public static final LocalDate DIRECTORY_FROM = LocalDate.of(2025, 1, 1);
    public static final YearMonth FIRST_MONTH = YearMonth.of(2026, 3);
    public static final YearMonth OPEN_MONTH = YearMonth.of(2028, 2);
    public static final LocalDate LAST_DAY = LocalDate.of(2028, 2, 29);
    public static final OffsetDateTime ESTIMATE_AT = OffsetDateTime.parse("2028-03-01T02:10:00+09:00");
    public static final OffsetDateTime START = OffsetDateTime.parse("2026-03-01T00:00:00+09:00");
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final int AGENTS = 30;
    private static final int BASE_PER_AGENT = 14;

    /** 설계사 한 명. */
    public record Agent(String agentId, String name, String grade, String team, String branch, String hq) {
    }

    /**
     * 계약 한 건. {@code cancelAt}·{@code reduceAt}은 그 회차가 될 날에 해약·감액한다(없으면 {@code null}).
     *
     * @param caseId 필수 케이스 계약이면 그 이름, 기본 계약이면 {@code null}
     */
    public record Contract(String policyNo, String agentId, LocalDate contractDate, long premium, long incentive, Integer cancelAt,
                           Integer reduceAt, long reducedPremium, String caseId) {
    }

    /** 실행 한 단계 — 같은 시각이면 {@code order}, 그다음 넣은 순서. */
    public record Step(OffsetDateTime at, int order, Consumer<OutboxEngine> run) {
    }

    public static final String CASE_LIMIT_80 = "LIMIT_80";
    public static final String CASE_LIMIT_95 = "LIMIT_95";
    public static final String CASE_LIMIT_CUT = "LIMIT_100_CUT";
    public static final String CASE_LIMIT_DEFER = "LIMIT_100_DEFER_AFTER_FY";
    public static final String CASE_HELD = "DEFERRAL_HELD";
    public static final String CASE_LIMIT_0630 = "BOUNDARY_LIMIT_20260630";
    public static final String CASE_LIMIT_0701 = "BOUNDARY_LIMIT_20260701";
    public static final String CASE_CURVE_1231 = "BOUNDARY_CURVE_20261231";
    public static final String CASE_CURVE_0101 = "BOUNDARY_CURVE_20270101";
    public static final String CASE_REPRICE = "REPRICE";
    public static final String CASE_RECEIVABLE = "RECEIVABLE_CARRIED";
    public static final String CASE_REBOOK = "REVERSAL_REBOOK";
    public static final String CASE_ADJUSTMENT = "ADJUSTMENT_AFTER_CLOSE";

    /** HELD 계약 — 2027-01 체결(커브 분급), 202801부터 유지 아님 → 첫 이연분(202801 도래)이 HELD. */
    public static final String HELD_POLICY = "DEMO-S05";
    private static final CloseYm HELD_FROM = CloseYm.of("202801");

    private final List<Agent> agents = new ArrayList<>();
    private final List<Contract> contracts = new ArrayList<>();

    private DemoScenario() {
        String[][] teams = {{"T1", "B1"}, {"T2", "B1"}, {"T3", "B1"}, {"T4", "B2"}, {"T5", "B2"}, {"T6", "B2"}};
        for (int i = 0; i < AGENTS; i++) {
            String[] team = teams[i / 5];
            agents.add(new Agent(agentId(i), "데모설계사%02d".formatted(i + 1), i % 2 == 0 ? RuleFixtures.GRADE_SENIOR : RuleFixtures.GRADE_JUNIOR,
                    team[0], team[1], "H1"));
        }
        // 필수 케이스(부록 B) — 등급 SR(짝수 번째), 월납 100,000이면 한도 1,200,000. SR 초년도 = 신계약 630,000 + 2~12회차 각 13,500.
        special("DEMO-S01", 0, "2026-11-02", 100_000, 190_000, null, null, 0, CASE_LIMIT_80);    // 끝 누계 968,500 = 80.7%
        special("DEMO-S02", 2, "2026-11-03", 100_000, 370_000, null, null, 0, CASE_LIMIT_95);    // 끝 누계 1,148,500 = 95.7%
        special("DEMO-S03", 4, "2026-08-04", 100_000, 1_000_000, null, null, 0, CASE_LIMIT_CUT);
        special("DEMO-S04", 6, "2026-10-05", 100_000, 1_000_000, null, null, 0, CASE_LIMIT_DEFER);
        special(HELD_POLICY, 8, "2027-01-15", 150_000, 0, null, null, 0, CASE_HELD);
        special("DEMO-S06", 10, "2026-06-30", 150_000, 0, null, null, 0, CASE_LIMIT_0630);
        special("DEMO-S07", 12, "2026-07-01", 150_000, 0, null, null, 0, CASE_LIMIT_0701);
        special("DEMO-S08", 14, "2026-12-31", 150_000, 0, null, null, 0, CASE_CURVE_1231);
        special("DEMO-S09", 16, "2027-01-01", 150_000, 0, null, null, 0, CASE_CURVE_0101);
        special("DEMO-S10", 18, "2026-08-10", 200_000, 0, null, 5, 80_000, CASE_REPRICE);
        special("DEMO-S11", 20, "2026-09-07", 150_000, 0, null, null, 0, CASE_REBOOK);
        special("DEMO-S12", 22, "2026-10-12", 150_000, 0, null, null, 0, CASE_ADJUSTMENT);
        int policy = 0;
        for (int i = 0; i < AGENTS; i++) {
            for (int c = 0; c < BASE_PER_AGENT; c++) {
                policy++;
                YearMonth ym = FIRST_MONTH.plusMonths((i * 7L + c * 5L) % 16);
                LocalDate date = ym.atDay(1 + (i * 3 + c * 11) % 28);
                long premium = 50_000 + ((i * 31L + c * 17L) % 20) * 10_000;
                long incentive = c % 5 == 0 ? 200_000 : 0;
                boolean cancels = (i + c) % 13 == 0;
                Integer cancelAt = cancels ? 3 + (i + c) % 10 : null;
                boolean reduces = !cancels && (i * 3 + c) % 37 == 0;
                long reduced = Math.max(30_000, premium / 2 / 10_000 * 10_000);
                contracts.add(new Contract("DEMO-%04d".formatted(policy), agentId(i), date, premium, incentive, cancelAt,
                        reduces ? 6 : null, reduces ? reduced : 0, null));
            }
        }
    }

    private void special(String policyNo, int agent, String date, long premium, long incentive, Integer cancelAt, Integer reduceAt,
                         long reduced, String caseId) {
        contracts.add(new Contract(policyNo, agentId(agent), LocalDate.parse(date), premium, incentive, cancelAt, reduceAt, reduced, caseId));
    }

    public static String agentId(int index) {
        return "A-%04d".formatted(2001 + index);
    }

    public static DemoScenario standard() {
        return new DemoScenario();
    }

    public List<Agent> agents() {
        return List.copyOf(agents);
    }

    public List<Contract> contracts() {
        return List.copyOf(contracts);
    }

    public Contract caseContract(String caseId) {
        return contracts.stream().filter(c -> caseId.equals(c.caseId())).findFirst().orElseThrow();
    }

    /** 표준 룰 + 한도 규칙 기간 분할(P-4). 두 규칙의 기간은 겹치지 않는다. */
    public InMemoryRuleStore rules() {
        LimitRule cut = new LimitRule(1, ChannelType.GA_TO_AGENT, new BigDecimal("12.00"), 12, OverLimitAction.CUT, true,
                EffectivePeriod.of(RuleFixtures.GA_LIMIT_FROM, LocalDate.of(2026, 9, 30)));
        LimitRule defer = new LimitRule(2, ChannelType.GA_TO_AGENT, new BigDecimal("12.00"), 12, OverLimitAction.DEFER_AFTER_FY, true,
                EffectivePeriod.from(LocalDate.of(2026, 10, 1)));
        return RuleFixtures.standardRules(List.of(cut, defer));
    }

    public InMemoryAgentDirectory directory() {
        InMemoryAgentDirectory d = new InMemoryAgentDirectory();
        for (Agent a : agents) {
            AgentId id = new AgentId(a.agentId());
            d.withGrade(id, a.grade(), DIRECTORY_FROM);
            d.withOrgChain(id, DIRECTORY_FROM, InMemoryAgentDirectory.team(a.team()), InMemoryAgentDirectory.branch(a.branch()),
                    InMemoryAgentDirectory.hq(a.hq()));
        }
        return d;
    }

    /** HELD 계약만 202801부터 유지 아님(P-4). */
    public PolicyStatusProvider policyStatus() {
        return (policyNo, asOf) -> !(policyNo.value().equals(HELD_POLICY) && !asOf.isBefore(HELD_FROM));
    }

    /** 잠정 명세 직전까지의 단계(시각 순). 잠정 명세는 {@link #ESTIMATE_AT}에 따로 — 그 직전에 명세 샘플을 받는다. */
    public List<Step> steps() {
        List<Step> steps = new ArrayList<>();
        Map<String, PolicyEvent> stored = new HashMap<>();
        for (Contract c : contracts) {
            PolicyNo no = new PolicyNo(c.policyNo());
            AgentId agent = new AgentId(c.agentId());
            Map<String, String> attrs = c.incentive() > 0 ? Map.of("incentive_amount", String.valueOf(c.incentive())) : Map.of();
            PolicyEvent nw = event(c, EventType.NEW, c.contractDate(), c.premium(), null, null, attrs, "NEW");
            steps.add(new Step(at(c.contractDate(), 9, 0), 0, e -> {
                List<CommCalcRecord> produced = e.calculator.process(nw);
                stored.put(c.policyNo(), nw.withEventId(produced.getFirst().eventId()));
            }));
            long premium = c.premium();
            for (int k = 2; k <= 24; k++) {
                LocalDate due = c.contractDate().plusMonths(k - 1);
                if (due.isAfter(LAST_DAY)) {
                    break;
                }
                if (c.cancelAt() != null && k == c.cancelAt()) {
                    PolicyEvent cancel = event(c, EventType.CANCEL, due, premium, null, k - 1, Map.of(), "CANCEL");
                    steps.add(new Step(at(due, 9, 30), 0, e -> e.calculator.process(cancel)));
                    break;
                }
                if (c.reduceAt() != null && k == c.reduceAt()) {
                    PolicyEvent reduce = event(c, EventType.REDUCE, due, premium, null, null,
                            Map.of("new_monthly_premium", String.valueOf(c.reducedPremium())), "REDUCE");
                    steps.add(new Step(at(due, 8, 30), 0, e -> e.calculator.process(reduce)));
                    premium = c.reducedPremium();
                }
                PolicyEvent pay = event(c, EventType.PAYMENT, due, premium, premium, k, Map.of(), "PAYMENT:" + k);
                steps.add(new Step(at(due, 9, 0), 0, e -> e.calculator.process(pay)));
            }
            if (CASE_REBOOK.equals(c.caseId())) {
                // 마감 전 재분개(취소·재분개 짝) — 같은 달 안
                steps.add(new Step(at(c.contractDate().plusDays(13), 12, 0), 0,
                        e -> e.revision.rebookEvent(stored.get(c.policyNo()), e.calculator, "보험사 정정 명세 반영")));
            }
            if (CASE_ADJUSTMENT.equals(c.caseId())) {
                // 마감 뒤 조정 — 그 달 FY_COMM 설계사 라인에 -20,000(다음 열린 달로 귀속)
                CloseYm ym = CloseYm.from(c.contractDate());
                LocalDate next = c.contractDate().withDayOfMonth(1).plusMonths(1);
                steps.add(new Step(at(next.withDayOfMonth(6), 10, 0), 0, e -> {
                    long calcId = e.raw.calcs().findByCloseYm(ym).stream()
                            .filter(r -> r.recipientType() == RecipientType.AGENT && r.recipientId().equals(c.agentId())
                                    && r.commType().equals(CommTypeCode.FY_COMM) && r.policyNo().value().equals(c.policyNo()))
                            .findFirst().orElseThrow().calcId();
                    e.adjustmentService.adjust(calcId, "보험사 정정 명세", Money.won(-20_000), "정산팀장");
                }));
            }
        }
        for (YearMonth ym = FIRST_MONTH; ym.isBefore(OPEN_MONTH); ym = ym.plusMonths(1)) {
            CloseYm close = CloseYm.of(ym.getYear(), ym.getMonthValue());
            YearMonth next = ym.plusMonths(1);
            steps.add(new Step(at(next.atDay(5), 18, 0), 1, e -> e.closeService.close(close, "정산담당")));
            steps.add(new Step(at(next.atDay(10), 10, 0), 2, e -> e.payoutService.run(close, 1)));
        }
        steps.sort(Comparator.comparing(Step::at).thenComparingInt(Step::order));
        return steps;
    }

    /** 잠정 명세 직전까지 돌린다. */
    public void runUntilEstimate(OutboxEngine e, SteppingClock clock) {
        for (Step s : steps()) {
            clock.set(s.at());
            s.run().accept(e);
        }
        clock.set(ESTIMATE_AT);
    }

    /** 열린 달(202802) 잠정 명세 발행. */
    public void publishEstimate(OutboxEngine e) {
        e.estimateAndPublish(CloseYm.of(OPEN_MONTH.getYear(), OPEN_MONTH.getMonthValue()));
    }

    private static PolicyEvent event(Contract c, EventType type, LocalDate date, long premium, Long payment, Integer installment,
                                     Map<String, String> attrs, String keySuffix) {
        return new PolicyEvent(null, RuleFixtures.INSURER.value() + ":" + c.policyNo() + ":" + keySuffix, new PolicyNo(c.policyNo()),
                RuleFixtures.INSURER, RuleFixtures.PRODUCT, type, date, c.contractDate(), new AgentId(c.agentId()), Money.won(premium),
                payment == null ? null : Money.won(payment), installment, attrs);
    }

    private static OffsetDateTime at(LocalDate date, int hour, int minute) {
        return date.atTime(LocalTime.of(hour, minute)).atOffset(KST);
    }

    /** 설계사 ID → 계약들(입력 순서). */
    public Map<String, List<Contract>> byAgent() {
        Map<String, List<Contract>> out = new LinkedHashMap<>();
        for (Agent a : agents) {
            out.put(a.agentId(), new ArrayList<>());
        }
        for (Contract c : contracts) {
            out.get(c.agentId()).add(c);
        }
        return out;
    }
}
