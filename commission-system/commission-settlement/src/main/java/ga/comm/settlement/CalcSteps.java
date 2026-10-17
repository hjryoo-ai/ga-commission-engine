package ga.comm.settlement;

import ga.comm.calc.CalcResultListener;
import ga.comm.calc.StepConfig;
import ga.comm.calc.step.BaseCommissionStep;
import ga.comm.calc.step.IncentiveStep;
import ga.comm.calc.step.IncentiveV2Step;
import ga.comm.calc.step.OverrideCommissionStep;
import ga.comm.calc.step.PayoutRateStep;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.clawback.ClawbackPostProcessor;
import ga.comm.clawback.ClawbackStep;
import ga.comm.clawback.ReviveStep;
import ga.comm.deferral.DeferralCancelListener;
import ga.comm.deferral.DeferralReleaseService;
import ga.comm.deferral.DeferralSchedulePoster;
import ga.comm.deferral.DeferralScheduleStore;
import ga.comm.deferral.DeferralSplitStep;
import ga.comm.limit.LimitGateStep;
import ga.comm.limit.LimitLedgerPoster;
import ga.comm.limit.LimitLedgerService;
import ga.comm.limit.LimitLedgerStore;
import ga.comm.limit.PremiumRepriceStep;
import ga.comm.rule.IncentiveRepository;
import ga.comm.rule.incentive.IncentiveConditionEvaluator;
import ga.comm.rule.model.EffectivePeriod;

import java.time.LocalDate;
import java.util.List;

/**
 * 계산 Step 목록과 저장 후 훅 — 운영 계산기(앱 조립)와 시뮬레이션 dry-run 파이프라인(E2 ②)이 <b>같은 함수</b>로 만든다(E2 계획 §2-②, 주입 S12).
 * 저장소만 다르다: 운영은 발행 데코레이터 빈, dry-run은 오버레이 저장소. Step 클래스는 바꾸지 않는다(B-12).
 *
 * <p>계산 모듈은 한도·환수·분급 Step을 모르므로(모듈 순환) 그 셋을 모두 의존하는 정산 모듈에 둔다.
 */
public final class CalcSteps {

    /** Step 유효 시작일(앱 조립과 같다). */
    public static final LocalDate STEPS_FROM = LocalDate.of(2026, 1, 1);

    private CalcSteps() {
    }

    public static List<StepConfig> standard(IncentiveRepository incentives, CommCalcStore calcs, LimitLedgerStore ledgers) {
        EffectivePeriod from = EffectivePeriod.from(STEPS_FROM);
        return List.of(
                new StepConfig(new BaseCommissionStep(), from, 10),
                new StepConfig(new PayoutRateStep(), from, 20),
                new StepConfig(new IncentiveStep(), from, 30),                      // V1 (이벤트 속성 시책)
                new StepConfig(new IncentiveV2Step(incentives, new IncentiveConditionEvaluator()), from, 31),   // V2 (INCENTIVE_MST 조건식)
                new StepConfig(new OverrideCommissionStep(), from, 40),
                new StepConfig(new ClawbackStep(calcs), from, 45),
                new StepConfig(new ReviveStep(calcs, ReviveStep.RevivePolicy.REPAY), from, 46),
                new StepConfig(new LimitGateStep(ledgers), from, 50),
                new StepConfig(new DeferralSplitStep(), from, 55),
                new StepConfig(new PremiumRepriceStep(ledgers), from, 60));
    }

    public static List<CalcResultListener> listeners(LimitLedgerStore ledgers, DeferralScheduleStore schedules,
                                                     DeferralReleaseService release) {
        return List.of(
                new LimitLedgerPoster(ledgers),
                new ClawbackPostProcessor(new LimitLedgerService(ledgers)),
                new DeferralSchedulePoster(schedules),
                // 해약·실효·철회 → 미도래 분급 취소(E1 Q3 — E1 이전부터 운영 조립에 빠져 있던 결함, 골든 조립과 같게)
                new DeferralCancelListener(release));
    }
}
