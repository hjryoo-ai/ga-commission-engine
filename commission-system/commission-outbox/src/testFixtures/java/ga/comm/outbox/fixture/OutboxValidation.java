package ga.comm.outbox.fixture;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 전역 검증기의 JVM 전역 집계(Phase E1 C1·C6) — 시험 태스크(포크 JVM) 하나에 하나. {@link OutboxValidationReport}가 태스크 끝에 파일로 쓴다.
 */
public final class OutboxValidation {

    private static final Map<String, Integer> VALIDATED = new TreeMap<>();
    private static final Map<String, Integer> VIOLATIONS = new TreeMap<>();
    private static final Set<String> AGENT_IDS = new TreeSet<>();

    private OutboxValidation() {
    }

    static synchronized void validated(String type, String agentId) {
        VALIDATED.merge(type, 1, Integer::sum);
        if (agentId != null) {
            AGENT_IDS.add(agentId);
        }
    }

    static synchronized void violation(String type) {
        VIOLATIONS.merge(type, 1, Integer::sum);
    }

    public static synchronized Map<String, Integer> validatedByType() {
        return Map.copyOf(VALIDATED);
    }

    public static synchronized Map<String, Integer> violationsByType() {
        return Map.copyOf(VIOLATIONS);
    }

    /** 검증을 통과한 이벤트 payload의 {@code agentId} 전부(C6 — ORG 식별자와 교집합 0). */
    public static synchronized Set<String> agentIds() {
        return Set.copyOf(AGENT_IDS);
    }

    public static synchronized int total() {
        return VALIDATED.values().stream().mapToInt(Integer::intValue).sum();
    }
}
