package ga.comm.outbox.scenario;

import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.EventType;
import ga.comm.outbox.fixture.OutboxEngine;
import ga.comm.outbox.fixture.SteppingClock;

import java.util.Map;
import java.util.Objects;

/**
 * A-1 시나리오 실행기(E2 ⓪) — 업무 서비스를 {@link Services}로만 부른다. 인메모리({@link #of(OutboxEngine)})와 로컬 스택(⑤ — 앱 조립 빈)이
 * 같은 순서·같은 시각으로 돈다.
 */
public final class A1ScenarioRunner {

    /** 시나리오가 부르는 업무 서비스. */
    public interface Services {
        void process(PolicyEvent event);

        void close(CloseYm ym, String actor);

        void payout(CloseYm ym, int runSeq);

        /** 두 번째 판 전용 — 잠정 명세 잡({@code statementEstimateJob}과 같은 서비스). */
        void estimate(CloseYm ym);
    }

    private A1ScenarioRunner() {
    }

    /** 인메모리 시험 엔진의 서비스. */
    public static Services of(OutboxEngine e) {
        return new Services() {
            @Override
            public void process(PolicyEvent event) {
                e.calculator.process(event);
            }

            @Override
            public void close(CloseYm ym, String actor) {
                e.closeService.close(ym, actor);
            }

            @Override
            public void payout(CloseYm ym, int runSeq) {
                e.payoutService.run(ym, runSeq);
            }

            @Override
            public void estimate(CloseYm ym) {
                e.estimateAndPublish(ym);
            }
        };
    }

    /** 첫 판: 신계약(시책 정액) → 마감 → 지급 → 해약. */
    public static void firstEdition(A1Scenario s, Services services, SteppingClock clock) {
        Objects.requireNonNull(s);
        clock.set(s.times().calc());
        services.process(newContract(s));
        clock.set(s.times().close());
        services.close(s.closeYm(), "정산담당");
        clock.set(s.times().payout());
        services.payout(s.closeYm(), s.payoutRunSeq());
        clock.set(s.times().cancel());
        services.process(cancel(s));
    }

    /** 두 번째 판: 첫 판 + 해약 뒤 그 달 잠정 명세(첫 판 이벤트는 그대로 앞에 — Q8). */
    public static void secondEdition(A1Scenario s, Services services, SteppingClock clock) {
        firstEdition(s, services, clock);
        clock.set(s.times().estimate());
        services.estimate(s.estimateYm());
    }

    static PolicyEvent newContract(A1Scenario s) {
        PolicyNo policyNo = new PolicyNo(s.policyNo());
        return new PolicyEvent(null, key(s, EventType.NEW), policyNo, new InsurerCode(s.insurerCd()), new ProductKey(s.productKey()),
                EventType.NEW, s.contractDate(), s.contractDate(), new AgentId(s.agentId()), Money.won(s.monthlyPremium()), null, null,
                Map.of("incentive_amount", String.valueOf(s.incentiveAmount())));
    }

    static PolicyEvent cancel(A1Scenario s) {
        return new PolicyEvent(null, key(s, EventType.CANCEL), new PolicyNo(s.policyNo()), new InsurerCode(s.insurerCd()),
                new ProductKey(s.productKey()), EventType.CANCEL, s.cancelDate(), s.contractDate(), new AgentId(s.agentId()),
                Money.won(s.monthlyPremium()), null, s.elapsedInstallments(), Map.of());
    }

    private static String key(A1Scenario s, EventType type) {
        return s.insurerCd() + ":" + s.policyNo() + ":" + type;
    }
}
