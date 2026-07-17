package ga.comm.calc;

import java.util.LinkedHashMap;
import java.util.Map;

/** 계산 trace 1건 — Step이 남기는 단계별 중간값. COMM_CALC.calc_trace로 직렬화된다. */
public record TraceEntry(String stepId, String message, Map<String, String> values) {

    public TraceEntry {
        values = values == null ? Map.of() : new LinkedHashMap<>(values);
    }

    public static TraceEntry of(String stepId, String message) {
        return new TraceEntry(stepId, message, Map.of());
    }
}
