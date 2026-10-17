package ga.comm.calc;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 계산 Step 체인 실행기.
 * 활성 판정: StepConfig 유효기간이 이벤트 발생일을 포함 AND step.supports(ctx).
 */
public final class CalculationPipeline {

    private final List<StepConfig> configs;

    public CalculationPipeline(List<StepConfig> configs) {
        this.configs = configs.stream()
                .sorted(Comparator.comparingInt(StepConfig::order))
                .toList();
    }

    /** 조립된 Step 클래스(실행 순서) — 운영 조립과 dry-run 파이프라인의 일치 시험(E2 ②, 읽기 전용). */
    public java.util.List<Class<?>> stepClasses() {
        return configs.stream().<Class<?>>map(c -> c.step().getClass()).toList();
    }

    public void run(CalcContext ctx) {
        for (StepConfig config : configs) {
            if (!config.activePeriod().contains(ctx.event().eventDate())) {
                continue;
            }
            CalculationStep step = config.step();
            if (!step.supports(ctx)) {
                continue;
            }
            step.apply(ctx);
            ctx.trace(step.stepId(), "실행 완료",
                    Map.of("lines", String.valueOf(ctx.lines().size())));
        }
    }
}
