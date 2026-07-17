package ga.comm.calc.util;

import ga.comm.calc.RuleVersionRef;
import ga.comm.calc.TraceEntry;

import java.util.List;
import java.util.Map;

/**
 * COMM_CALC의 rule_versions / calc_trace 직렬화 전용 최소 JSON 작성기.
 * 외부 라이브러리 의존을 피하기 위한 것으로, 파싱은 하지 않는다(원장은 쓰기 전용).
 */
public final class JsonLite {

    private JsonLite() {
    }

    public static String ruleVersions(List<RuleVersionRef> refs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < refs.size(); i++) {
            RuleVersionRef r = refs.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"type\":").append(quote(r.ruleType()))
                    .append(",\"key\":").append(quote(r.key()))
                    .append(",\"ref\":").append(quote(r.versionRef()))
                    .append('}');
        }
        return sb.append(']').toString();
    }

    public static String trace(List<TraceEntry> entries) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            TraceEntry e = entries.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"step\":").append(quote(e.stepId()))
                    .append(",\"msg\":").append(quote(e.message()));
            if (!e.values().isEmpty()) {
                sb.append(",\"values\":{");
                boolean first = true;
                for (Map.Entry<String, String> v : e.values().entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append(quote(v.getKey())).append(':').append(quote(v.getValue()));
                    first = false;
                }
                sb.append('}');
            }
            sb.append('}');
        }
        return sb.append(']').toString();
    }

    /** trace 문자열 필드 인용 — RevisionService 등 외부 조립용. */
    public static String quoteForTrace(String s) {
        return quote(s);
    }

    static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
