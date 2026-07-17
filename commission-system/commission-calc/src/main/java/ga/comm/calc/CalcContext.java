package ga.comm.calc;

import ga.comm.calc.recipient.CalcTarget;
import ga.comm.domain.event.PolicyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 계산 컨텍스트 — 파이프라인을 흐르는 누적 객체. 계산 단위는 (계약이벤트 × 수급자).
 * 이벤트/수급자/룰 스냅샷은 불변이고, 라인·trace만 Step에 의해 누적된다.
 */
public final class CalcContext {

    private final PolicyEvent event;
    private final CalcTarget target;
    private final RuleSnapshot rules;
    private final List<CalcLine> lines = new ArrayList<>();
    private final List<TraceEntry> trace = new ArrayList<>();
    private final Map<String, Object> attachments = new LinkedHashMap<>();
    private LimitLedgerView limitView;

    public CalcContext(PolicyEvent event, CalcTarget target, RuleSnapshot rules) {
        this.event = Objects.requireNonNull(event);
        this.target = Objects.requireNonNull(target);
        this.rules = Objects.requireNonNull(rules);
    }

    public PolicyEvent event() {
        return event;
    }

    public CalcTarget target() {
        return target;
    }

    public RuleSnapshot rules() {
        return rules;
    }

    public List<CalcLine> lines() {
        return Collections.unmodifiableList(lines);
    }

    public void addLine(CalcLine line) {
        lines.add(line);
    }

    /** index 위치의 라인을 치환한다 (지급률/한도 게이트 적용). */
    public void replaceLine(int index, CalcLine line) {
        lines.set(index, line);
    }

    public List<TraceEntry> traceEntries() {
        return Collections.unmodifiableList(trace);
    }

    public void trace(String stepId, String message, Map<String, String> values) {
        trace.add(new TraceEntry(stepId, message, values == null ? Map.of() : new LinkedHashMap<>(values)));
    }

    public void trace(String stepId, String message) {
        trace(stepId, message, Map.of());
    }

    public Optional<LimitLedgerView> limitView() {
        return Optional.ofNullable(limitView);
    }

    public void attachLimitView(LimitLedgerView view) {
        this.limitView = view;
    }

    /** Step 간·Step→리스너 간 전달용 부가 데이터 (예: 한도 원장 전기 대기 목록). */
    public void putAttachment(String key, Object value) {
        attachments.put(key, value);
    }

    public <T> Optional<T> attachment(String key, Class<T> type) {
        return Optional.ofNullable(attachments.get(key)).map(type::cast);
    }
}
