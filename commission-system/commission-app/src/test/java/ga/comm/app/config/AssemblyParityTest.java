package ga.comm.app.config;

import ga.comm.app.support.PlaceholderPolicyStatusProvider;
import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.fixture.InMemoryAgentDirectory;
import ga.comm.calc.fixture.InMemoryCommCalcStore;
import ga.comm.calc.fixture.InMemoryPolicyEventStore;
import ga.comm.calc.recipient.AffiliationBasis;
import ga.comm.calc.recipient.RecipientResolver;
import ga.comm.calc.revision.RevisionService;
import ga.comm.deferral.DeferralReleaseService;
import ga.comm.deferral.fixture.InMemoryDeferralScheduleStore;
import ga.comm.golden.GoldenEngine;
import ga.comm.limit.fixture.InMemoryLimitLedgerStore;
import ga.comm.rule.fixture.InMemoryIncentiveStore;
import ga.comm.rule.fixture.InMemoryRuleStore;
import ga.comm.settlement.SettleCloseStore;
import ga.comm.settlement.fixture.InMemorySettleCloseStore;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 조립 = 골든 조립(Phase E1 Q3). 앱 설정의 {@code @Bean} 메서드를 그대로 불러(스프링·DB 없이) 계산기의 저장 후 훅과 정정 서비스의
 * reversal 훅 클래스 집합을 골든 엔진(전 피처)과 비교한다. E1 이전에는 분급 취소 리스너·reversal 훅이 운영 조립에만 빠져 있었다 —
 * 골든이 통과해도 운영에서 스케줄이 CANCELLED가 되지 않았다. 다시 어긋나면 이 시험이 실패한다(주입 I13).
 */
class AssemblyParityTest {

    private final InMemoryCommCalcStore calcStore = new InMemoryCommCalcStore();
    private final InMemoryDeferralScheduleStore scheduleStore = new InMemoryDeferralScheduleStore();
    private final InMemoryLimitLedgerStore ledgerStore = new InMemoryLimitLedgerStore();
    private final SettleCloseStore closeStore = new InMemorySettleCloseStore();

    private static Set<Class<?>> classes(java.util.List<?> instances) {
        Set<Class<?>> out = new HashSet<>();
        instances.forEach(i -> out.add(i.getClass()));
        return out;
    }

    @Test
    void 계산기의_저장_후_훅은_골든_조립과_같다() {
        DeferralReleaseService release = new ServiceConfig().deferralReleaseService(scheduleStore, calcStore,
                new PlaceholderPolicyStatusProvider());
        CalcPipelineConfig pipeline = new CalcPipelineConfig();
        CommissionCalculator calculator = pipeline.commissionCalculator(new InMemoryRuleStore(), new InMemoryIncentiveStore(),
                new RecipientResolver(new InMemoryAgentDirectory(), AffiliationBasis.EVENT_DATE), new InMemoryPolicyEventStore(),
                calcStore, ledgerStore, scheduleStore, pipeline.closeYmResolver(SettleCloseStore.asProvider(closeStore)), release);

        assertThat(classes(calculator.listeners()))
                .isEqualTo(new HashSet<>(GoldenEngine.fullAssembly().listeners()))
                .hasSize(4);
    }

    @Test
    void 정정_서비스의_reversal_훅은_골든_조립과_같다() {
        RevisionService revision = new ServiceConfig().revisionService(calcStore, SettleCloseStore.asProvider(closeStore),
                ledgerStore, scheduleStore);

        assertThat(classes(revision.hooks()))
                .isEqualTo(new HashSet<>(GoldenEngine.fullAssembly().reversalHooks()))
                .hasSize(2);
    }
}
