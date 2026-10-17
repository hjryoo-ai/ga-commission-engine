package ga.comm.app.config;

import ga.comm.calc.CalcResultListener;
import ga.comm.calc.CalculationPipeline;
import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.StepConfig;
import ga.comm.calc.recipient.AffiliationBasis;
import ga.comm.calc.recipient.RecipientResolver;
import ga.comm.calc.store.CloseStatusProvider;
import ga.comm.calc.store.CloseYmResolver;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.calc.store.PolicyEventStore;
import ga.comm.deferral.DeferralReleaseService;
import ga.comm.deferral.DeferralScheduleStore;
import ga.comm.domain.time.CloseYm;
import ga.comm.limit.LimitLedgerStore;
import ga.comm.rule.IncentiveRepository;
import ga.comm.rule.RuleRepository;
import ga.comm.calc.recipient.AgentDirectory;
import ga.comm.settlement.CalcSteps;
import ga.comm.settlement.SettleCloseStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 계산 파이프라인 배선 (Phase 16) — 전 Step 체인·리스너·수급자 해석기·귀속월 해석기를 조립한다.
 * 골든셋 러너·Oracle IT와 동일한 조립을 <b>실 앱 컨텍스트</b>로 옮긴 것이다(스토어는 Oracle 어댑터).
 *
 * <p>Step은 공통 시행일부터 활성으로 두고 적용·경계는 시드 룰의 {@code apply_from}이 구동한다(룰=데이터).
 * 귀속월은 마감 상태를 따른다(CLOSED면 다음 OPEN 월 — §6.4 마감월 불변성).
 */
@Configuration(proxyBeanMethods = false)
public class CalcPipelineConfig {

    @Bean
    public CloseStatusProvider closeStatusProvider(SettleCloseStore settleCloseStore) {
        return SettleCloseStore.asProvider(settleCloseStore);
    }

    @Bean
    public CloseYmResolver closeYmResolver(CloseStatusProvider closeStatusProvider) {
        return event -> closeStatusProvider.attributionFor(CloseYm.from(event.eventDate()));
    }

    @Bean
    public RecipientResolver recipientResolver(AgentDirectory agentDirectory) {
        // 오버라이드 귀속 기준(§11 #6): 기본 EVENT_DATE(발생 시점 소속). 확정 시 프로퍼티로 외부화.
        return new RecipientResolver(agentDirectory, AffiliationBasis.EVENT_DATE);
    }

    @Bean
    public CommissionCalculator commissionCalculator(RuleRepository ruleRepository,
                                                     IncentiveRepository incentiveRepository,
                                                     RecipientResolver recipientResolver,
                                                     PolicyEventStore policyEventStore,
                                                     CommCalcStore commCalcStore,
                                                     LimitLedgerStore limitLedgerStore,
                                                     DeferralScheduleStore deferralScheduleStore,
                                                     CloseYmResolver closeYmResolver,
                                                     DeferralReleaseService deferralReleaseService) {
        // Step 목록·저장 후 훅 = CalcSteps(시뮬레이션 dry-run 파이프라인과 같은 함수 — E2 ②)
        List<StepConfig> steps = CalcSteps.standard(incentiveRepository, commCalcStore, limitLedgerStore);
        List<CalcResultListener> listeners = CalcSteps.listeners(limitLedgerStore, deferralScheduleStore, deferralReleaseService);

        return new CommissionCalculator(ruleRepository, recipientResolver,
                new CalculationPipeline(steps), policyEventStore, commCalcStore,
                closeYmResolver, listeners);
    }
}
