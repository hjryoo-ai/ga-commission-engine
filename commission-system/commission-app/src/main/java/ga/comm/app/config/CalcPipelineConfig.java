package ga.comm.app.config;

import ga.comm.calc.CalcResultListener;
import ga.comm.calc.CalculationPipeline;
import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.StepConfig;
import ga.comm.calc.recipient.AffiliationBasis;
import ga.comm.calc.recipient.RecipientResolver;
import ga.comm.calc.step.BaseCommissionStep;
import ga.comm.calc.step.IncentiveStep;
import ga.comm.calc.step.IncentiveV2Step;
import ga.comm.calc.step.OverrideCommissionStep;
import ga.comm.calc.step.PayoutRateStep;
import ga.comm.calc.store.CloseStatusProvider;
import ga.comm.calc.store.CloseYmResolver;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.calc.store.PolicyEventStore;
import ga.comm.clawback.ClawbackPostProcessor;
import ga.comm.clawback.ClawbackStep;
import ga.comm.clawback.ReviveStep;
import ga.comm.deferral.DeferralSchedulePoster;
import ga.comm.deferral.DeferralScheduleStore;
import ga.comm.deferral.DeferralSplitStep;
import ga.comm.domain.time.CloseYm;
import ga.comm.limit.LimitGateStep;
import ga.comm.limit.LimitLedgerPoster;
import ga.comm.limit.LimitLedgerService;
import ga.comm.limit.LimitLedgerStore;
import ga.comm.limit.PremiumRepriceStep;
import ga.comm.rule.IncentiveRepository;
import ga.comm.rule.RuleRepository;
import ga.comm.rule.incentive.IncentiveConditionEvaluator;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.calc.recipient.AgentDirectory;
import ga.comm.settlement.SettleCloseStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
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

    private static final LocalDate STEPS_FROM = LocalDate.of(2026, 1, 1);

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
                                                     CloseYmResolver closeYmResolver) {
        EffectivePeriod from = EffectivePeriod.from(STEPS_FROM);
        List<StepConfig> steps = List.of(
                new StepConfig(new BaseCommissionStep(), from, 10),
                new StepConfig(new PayoutRateStep(), from, 20),
                new StepConfig(new IncentiveStep(), from, 30),                      // V1 (이벤트 속성 시책)
                new StepConfig(new IncentiveV2Step(incentiveRepository,
                        new IncentiveConditionEvaluator()), from, 31),             // V2 (INCENTIVE_MST 조건식)
                new StepConfig(new OverrideCommissionStep(), from, 40),
                new StepConfig(new ClawbackStep(commCalcStore), from, 45),
                new StepConfig(new ReviveStep(commCalcStore, ReviveStep.RevivePolicy.REPAY), from, 46),
                new StepConfig(new LimitGateStep(limitLedgerStore), from, 50),
                new StepConfig(new DeferralSplitStep(), from, 55),
                new StepConfig(new PremiumRepriceStep(limitLedgerStore), from, 60));

        List<CalcResultListener> listeners = List.of(
                new LimitLedgerPoster(limitLedgerStore),
                new ClawbackPostProcessor(new LimitLedgerService(limitLedgerStore)),
                new DeferralSchedulePoster(deferralScheduleStore));

        return new CommissionCalculator(ruleRepository, recipientResolver,
                new CalculationPipeline(steps), policyEventStore, commCalcStore,
                closeYmResolver, listeners);
    }
}
