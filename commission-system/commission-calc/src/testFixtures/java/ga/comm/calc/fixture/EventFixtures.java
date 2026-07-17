package ga.comm.calc.fixture;

import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.type.EventType;
import ga.comm.rule.fixture.RuleFixtures;

import java.time.LocalDate;
import java.util.Map;

/**
 * 표준 이벤트 픽스처 — 설계서 부록 A 시나리오(2026-08-01 체결, 월납 30만, 설계사 A).
 */
public final class EventFixtures {

    public static final AgentId AGENT_A = new AgentId("A-1001");
    public static final PolicyNo POLICY_1 = new PolicyNo("POL-2026-0001");
    public static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 8, 1);
    public static final Money MONTHLY_PREMIUM = Money.won(300_000);

    private EventFixtures() {
    }

    /** 신계약 체결 이벤트. */
    public static PolicyEvent newContract() {
        return newContract(POLICY_1, CONTRACT_DATE, MONTHLY_PREMIUM, Map.of());
    }

    public static PolicyEvent newContract(PolicyNo policyNo, LocalDate contractDate,
                                          Money monthlyPremium, Map<String, String> attributes) {
        return new PolicyEvent(null,
                eventKey(policyNo, EventType.NEW, null),
                policyNo, RuleFixtures.INSURER, RuleFixtures.PRODUCT,
                EventType.NEW, contractDate, contractDate, AGENT_A,
                monthlyPremium, null, null, attributes);
    }

    /** 회차 보험료 입금 이벤트. */
    public static PolicyEvent payment(int installmentNo, LocalDate paidOn) {
        return payment(POLICY_1, CONTRACT_DATE, installmentNo, paidOn, MONTHLY_PREMIUM, Map.of());
    }

    public static PolicyEvent payment(PolicyNo policyNo, LocalDate contractDate, int installmentNo,
                                      LocalDate paidOn, Money amount, Map<String, String> attributes) {
        return new PolicyEvent(null,
                eventKey(policyNo, EventType.PAYMENT, installmentNo),
                policyNo, RuleFixtures.INSURER, RuleFixtures.PRODUCT,
                EventType.PAYMENT, paidOn, contractDate, AGENT_A,
                MONTHLY_PREMIUM, amount, installmentNo, attributes);
    }

    /** 감액(보험료 변경) 이벤트 — 한도 재산정 트리거. */
    public static PolicyEvent reduce(PolicyNo policyNo, LocalDate contractDate, LocalDate eventDate,
                                     Money newMonthlyPremium) {
        return new PolicyEvent(null,
                eventKey(policyNo, EventType.REDUCE, null),
                policyNo, RuleFixtures.INSURER, RuleFixtures.PRODUCT,
                EventType.REDUCE, eventDate, contractDate, AGENT_A,
                MONTHLY_PREMIUM, null, null,
                Map.of("new_monthly_premium", String.valueOf(newMonthlyPremium.toLong())));
    }

    /** 해약/실효/철회 등 계약 종료성 이벤트. */
    public static PolicyEvent terminal(EventType type, PolicyNo policyNo, LocalDate contractDate,
                                       LocalDate eventDate, Integer elapsedInstallments) {
        return new PolicyEvent(null,
                eventKey(policyNo, type, null),
                policyNo, RuleFixtures.INSURER, RuleFixtures.PRODUCT,
                type, eventDate, contractDate, AGENT_A,
                MONTHLY_PREMIUM, null, elapsedInstallments, Map.of());
    }

    public static String eventKey(PolicyNo policyNo, EventType type, Integer installmentNo) {
        return RuleFixtures.INSURER.value() + ":" + policyNo.value() + ":" + type
                + (installmentNo == null ? "" : ":" + installmentNo);
    }
}
