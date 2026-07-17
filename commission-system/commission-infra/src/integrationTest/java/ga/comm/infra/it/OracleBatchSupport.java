package ga.comm.infra.it;

import ga.comm.batch.BatchRequestRunner;
import ga.comm.batch.BatchRuntime;
import ga.comm.calc.CalcResultListener;
import ga.comm.calc.CalculationPipeline;
import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.StepConfig;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.step.BaseCommissionStep;
import ga.comm.calc.step.IncentiveStep;
import ga.comm.calc.step.OverrideCommissionStep;
import ga.comm.calc.step.PayoutRateStep;
import ga.comm.calc.recipient.AffiliationBasis;
import ga.comm.calc.recipient.RecipientResolver;
import ga.comm.calc.store.CloseYmResolver;
import ga.comm.infra.OraclePersistence;
import ga.comm.limit.LimitGateStep;
import ga.comm.limit.LimitLedgerPoster;
import ga.comm.rule.fixture.InMemoryRuleStore;
import ga.comm.rule.model.EffectivePeriod;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 실 Oracle 배치 하네스 (Phase 11): 잡 저장소(BATCH_*)는 V101 Flyway 스키마 위에서 동작하고,
 * 계산 파이프라인은 OracleLimitRaceIT와 동일한 실 배선(Oracle Store + 한도 게이트 + 저장 후 훅)이다.
 */
final class OracleBatchSupport {

    private static final LocalDate STEPS_FROM = LocalDate.of(2026, 1, 1);

    private static BatchRuntime runtime;
    private static BatchRequestRunner runner;

    private OracleBatchSupport() {
    }

    static synchronized BatchRuntime runtime() {
        if (runtime == null) {
            OraclePersistence persistence = OracleTestSupport.persistence();
            runtime = new BatchRuntime(OracleTestSupport.dataSource(),
                    persistence.txManager(), "ORACLE");
            runner = new BatchRequestRunner(runtime.jobRepository(), runtime.jobLauncher());
        }
        return runtime;
    }

    static synchronized BatchRequestRunner runner() {
        runtime();
        return runner;
    }

    /** 실 배선 계산기 — 룰/조직은 읽기 전용 인메모리 픽스처, 스토어·게이트·훅은 Oracle. */
    static CommissionCalculator calculator(OraclePersistence persistence, InMemoryRuleStore rules,
                                           CalcResultListener... extraListeners) {
        return calculator(persistence, rules, List.of(), extraListeners);
    }

    static CommissionCalculator calculator(OraclePersistence persistence, InMemoryRuleStore rules,
                                           List<StepConfig> extraSteps,
                                           CalcResultListener... extraListeners) {
        List<StepConfig> steps = new ArrayList<>(List.of(
                new StepConfig(new BaseCommissionStep(), EffectivePeriod.from(STEPS_FROM), 10),
                new StepConfig(new PayoutRateStep(), EffectivePeriod.from(STEPS_FROM), 20),
                new StepConfig(new IncentiveStep(), EffectivePeriod.from(STEPS_FROM), 30),
                new StepConfig(new OverrideCommissionStep(), EffectivePeriod.from(STEPS_FROM), 40),
                new StepConfig(new LimitGateStep(persistence.limitLedgerStore()),
                        EffectivePeriod.from(STEPS_FROM), 50)));
        steps.addAll(extraSteps);
        List<CalcResultListener> listeners = new ArrayList<>();
        listeners.add(new LimitLedgerPoster(persistence.limitLedgerStore()));
        listeners.addAll(List.of(extraListeners));
        return new CommissionCalculator(
                rules,
                new RecipientResolver(CalcTestHarness.standardDirectory(), AffiliationBasis.EVENT_DATE),
                new CalculationPipeline(steps),
                persistence.policyEventStore(),
                persistence.commCalcStore(),
                CloseYmResolver.byEventDate(),
                listeners);
    }

    static JobParameters closeParams(String closeYm, String closedBy, boolean force, String reason) {
        JobParametersBuilder builder = new JobParametersBuilder()
                .addString("close_ym", closeYm)
                .addString("closed_by", closedBy, false)
                .addString("force", force ? "Y" : "N", false);
        if (reason != null) {
            builder.addString("force_reason", reason, false);
        }
        return builder.toJobParameters();
    }

    static JobParameters payoutParams(String closeYm, int runSeq) {
        return new JobParametersBuilder()
                .addString("close_ym", closeYm)
                .addLong("run_seq", (long) runSeq)
                .toJobParameters();
    }

    static JobParameters recalcParams(String requestId, String eventIdsCsv, String reason) {
        return new JobParametersBuilder()
                .addString("request_id", requestId)
                .addString("event_ids", eventIdsCsv, false)
                .addString("reason", reason, false)
                .toJobParameters();
    }

    static JobParameters releaseParams(String closeYm) {
        return new JobParametersBuilder().addString("close_ym", closeYm).toJobParameters();
    }
}
