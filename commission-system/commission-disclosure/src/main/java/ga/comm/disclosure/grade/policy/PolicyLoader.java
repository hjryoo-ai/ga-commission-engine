package ga.comm.disclosure.grade.policy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 정책 body(JSON) 해석 + 로드 시 검증(Phase E3 작업 1·E2). 위반은 전부 모아 {@link InvalidPolicyException} 하나로 던진다.
 *
 * <p>등급 구간 규약: 모든 경계는 문자열 10진수(JSON 숫자 거부 — 부동소수 경유 차단)와 포함 여부 플래그를 <b>함께</b> 가진다
 * (기본값 없음, 부록 B-13). 하한 없는 구간과 상한 없는 구간이 정확히 1개씩이고, 하한 순으로 정렬했을 때 인접 경계의 값이 같고
 * 포함 여부가 상보적(한쪽만 포함)이어야 한다 — 둘 다 제외면 빈틈, 둘 다 포함이면 겹침. ordinal은 비율이 커질수록 엄격 증가
 * (수수료가 낮을수록 작은 정수, 계약 §4.1).
 */
public final class PolicyLoader {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,31}$");
    private static final Set<String> GRADING_KEYS = Set.of("measureKey", "measureParams", "population", "period",
            "asOfFutureDaysAllowed", "ratioScale", "ratioRounding", "grades", "unavailableReasons");
    private static final Set<String> POPULATION_KEYS = Set.of("groupCodeSystem", "scope", "minPopulation");
    private static final Set<String> PERIOD_KEYS = Set.of("kind", "asOfRule");
    private static final Set<String> GRADE_KEYS = Set.of("code", "label", "ordinal", "minRatio", "minInclusive", "maxRatio",
            "maxInclusive");
    private static final Set<String> RANKING_KEYS = Set.of("tieBreak", "secondaryKeys");
    /** v1 엔진이 만들어 낼 수 있는 원인 — 정책이 반드시 사유 코드를 매핑해야 한다. */
    private static final Set<UnavailableCause> REQUIRED_CAUSES = Set.of(UnavailableCause.NOT_IN_GROUP,
            UnavailableCause.NO_RATE_DATA, UnavailableCause.OUTSIDE_PERIOD, UnavailableCause.INSUFFICIENT_POPULATION);

    private PolicyLoader() {
    }

    public static GradingPolicySpec grading(String body) {
        List<String> problems = new ArrayList<>();
        JsonNode root = parse(body);
        onlyKeys(root, GRADING_KEYS, "", problems);

        String measureKey = text(root, "measureKey", problems);
        JsonNode measureParams = root.get("measureParams");
        if (measureParams == null || !measureParams.isObject()) {
            problems.add("measureParams: required object");
        }

        JsonNode population = object(root, "population", problems);
        onlyKeys(population, POPULATION_KEYS, "population.", problems);
        String groupCodeSystem = text(population, "groupCodeSystem", problems);
        PopulationScope scope = enumValue(population, "scope", PopulationScope.class, problems);
        int minPopulation = integer(population, "minPopulation", 1, Integer.MAX_VALUE, problems);

        JsonNode period = object(root, "period", problems);
        onlyKeys(period, PERIOD_KEYS, "period.", problems);
        PeriodKind kind = enumValue(period, "kind", PeriodKind.class, problems);
        AsOfRule asOfRule = enumValue(period, "asOfRule", AsOfRule.class, problems);

        int futureDays = integer(root, "asOfFutureDaysAllowed", 0, 3660, problems);
        int ratioScale = integer(root, "ratioScale", 0, 10, problems);
        RoundingMode rounding = enumValue(root, "ratioRounding", RoundingMode.class, problems);
        if (rounding == RoundingMode.UNNECESSARY) {
            problems.add("ratioRounding: UNNECESSARY is not a rounding mode");
        }

        List<GradeBand> grades = grades(root.get("grades"), problems);
        Map<UnavailableCause, String> reasons = reasons(root.get("unavailableReasons"), problems);

        if (!problems.isEmpty()) {
            throw new InvalidPolicyException(problems);
        }
        return new GradingPolicySpec(measureKey, measureParams.deepCopy(), groupCodeSystem, scope, minPopulation, kind, asOfRule,
                futureDays, ratioScale, rounding, grades, reasons);
    }

    public static RankingPolicySpec ranking(String body) {
        List<String> problems = new ArrayList<>();
        JsonNode root = parse(body);
        onlyKeys(root, RANKING_KEYS, "", problems);
        TieBreak tieBreak = enumValue(root, "tieBreak", TieBreak.class, problems);
        List<SecondaryKey> keys = new ArrayList<>();
        JsonNode secondary = root.get("secondaryKeys");
        if (secondary != null) {
            if (!secondary.isArray()) {
                problems.add("secondaryKeys: must be an array");
            } else {
                for (JsonNode k : secondary) {
                    SecondaryKey key = k.isTextual() ? parseEnum(SecondaryKey.class, k.textValue()) : null;
                    if (key == null) {
                        problems.add("secondaryKeys: unknown key " + k);
                    } else if (keys.contains(key)) {
                        problems.add("secondaryKeys: duplicate " + key);
                    } else {
                        keys.add(key);
                    }
                }
            }
        }
        if (tieBreak == TieBreak.STRICT && !keys.contains(SecondaryKey.PRODUCT_KEY_ASC)) {
            problems.add("secondaryKeys: STRICT needs PRODUCT_KEY_ASC so that the order is total (1..m permutation)");
        }
        if (tieBreak == TieBreak.SHARED_RANK && secondary != null) {
            problems.add("secondaryKeys: SHARED_RANK does not separate ties — remove secondaryKeys");
        }
        if (!problems.isEmpty()) {
            throw new InvalidPolicyException(problems);
        }
        return new RankingPolicySpec(tieBreak, keys);
    }

    // ------------------------------------------------------------------ 등급 구간

    private static List<GradeBand> grades(JsonNode node, List<String> problems) {
        List<GradeBand> bands = new ArrayList<>();
        if (node == null || !node.isArray() || node.isEmpty()) {
            problems.add("grades: required non-empty array");
            return bands;
        }
        Set<String> codes = new HashSet<>();
        Set<Integer> ordinals = new HashSet<>();
        int index = 0;
        for (JsonNode g : node) {
            String at = "grades[" + index++ + "].";
            if (!g.isObject()) {
                problems.add(at + ": must be an object");
                continue;
            }
            int before = problems.size();
            onlyKeys(g, GRADE_KEYS, at, problems);
            String code = text(g, "code", problems, at);
            if (code != null && !CODE.matcher(code).matches()) {
                problems.add(at + "code: must match " + CODE.pattern());
            }
            String label = text(g, "label", problems, at);
            int ordinal = integer(g, "ordinal", 1, 1000, problems, at);
            RatioBound min = bound(g, "minRatio", "minInclusive", at, problems);
            RatioBound max = bound(g, "maxRatio", "maxInclusive", at, problems);
            if (min != null && max != null && min.value().compareTo(max.value()) >= 0) {
                problems.add(at + "minRatio must be < maxRatio");
            }
            if (code != null && !codes.add(code)) {
                problems.add(at + "code: duplicate " + code);
            }
            if (!ordinals.add(ordinal)) {
                problems.add(at + "ordinal: duplicate " + ordinal);
            }
            if (problems.size() == before) {
                bands.add(new GradeBand(code, label, ordinal, min, max));
            }
        }
        if (bands.size() != node.size()) {
            return bands;   // 개별 구간 오류가 먼저 — 연속성 판정은 모든 구간이 온전할 때만
        }
        continuity(bands, problems);
        return bands;
    }

    private static void continuity(List<GradeBand> bands, List<String> problems) {
        long unboundedBelow = bands.stream().filter(b -> b.min() == null).count();
        long unboundedAbove = bands.stream().filter(b -> b.max() == null).count();
        if (unboundedBelow != 1 || unboundedAbove != 1) {
            problems.add("grades: exactly one band must have no minRatio and exactly one no maxRatio (found "
                    + unboundedBelow + " and " + unboundedAbove + ") — otherwise part of the ratio line has no grade");
            return;
        }
        List<GradeBand> sorted = new ArrayList<>(bands);
        sorted.sort(Comparator.comparing((GradeBand b) -> b.min() == null ? null : b.min().value(),
                Comparator.nullsFirst(Comparator.naturalOrder())));
        for (int i = 0; i + 1 < sorted.size(); i++) {
            GradeBand lower = sorted.get(i);
            GradeBand upper = sorted.get(i + 1);
            if (lower.max() == null || upper.min() == null) {
                problems.add("grades: " + lower.code() + " / " + upper.code() + " overlap (an unbounded side is not last)");
                continue;
            }
            int cmp = lower.max().value().compareTo(upper.min().value());
            if (cmp < 0) {
                problems.add("grades: gap between " + lower.code() + " (max " + lower.max().value().toPlainString() + ") and "
                        + upper.code() + " (min " + upper.min().value().toPlainString() + ")");
            } else if (cmp > 0) {
                problems.add("grades: " + lower.code() + " and " + upper.code() + " overlap");
            } else if (lower.max().inclusive() && upper.min().inclusive()) {
                problems.add("grades: " + lower.code() + " and " + upper.code() + " overlap at "
                        + lower.max().value().toPlainString() + " (both inclusive)");
            } else if (!lower.max().inclusive() && !upper.min().inclusive()) {
                problems.add("grades: gap at " + lower.max().value().toPlainString() + " between " + lower.code() + " and "
                        + upper.code() + " (both exclusive)");
            }
            if (upper.ordinal() <= lower.ordinal()) {
                problems.add("grades: ordinal must increase with the ratio (" + lower.code() + "=" + lower.ordinal() + ", "
                        + upper.code() + "=" + upper.ordinal() + ")");
            }
        }
    }

    private static RatioBound bound(JsonNode g, String valueKey, String inclusiveKey, String at, List<String> problems) {
        JsonNode value = g.get(valueKey);
        JsonNode inclusive = g.get(inclusiveKey);
        if (value == null) {
            if (inclusive != null) {
                problems.add(at + inclusiveKey + ": present without " + valueKey);
            }
            return null;
        }
        if (!value.isTextual()) {
            problems.add(at + valueKey + ": must be a decimal string (JSON numbers are rejected)");
            return null;
        }
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(value.textValue());
        } catch (NumberFormatException e) {
            problems.add(at + valueKey + ": not a decimal: " + value.textValue());
            return null;
        }
        if (decimal.signum() < 0) {
            problems.add(at + valueKey + ": must not be negative");
        }
        if (inclusive == null || !inclusive.isBoolean()) {
            problems.add(at + inclusiveKey + ": required boolean next to " + valueKey + " (no default)");
            return null;
        }
        return new RatioBound(decimal, inclusive.booleanValue());
    }

    private static Map<UnavailableCause, String> reasons(JsonNode node, List<String> problems) {
        Map<UnavailableCause, String> reasons = new EnumMap<>(UnavailableCause.class);
        if (node == null || !node.isObject()) {
            problems.add("unavailableReasons: required object {cause: reasonCode}");
            return reasons;
        }
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            UnavailableCause cause = parseEnum(UnavailableCause.class, e.getKey());
            if (cause == null) {
                problems.add("unavailableReasons: unknown cause " + e.getKey());
            } else if (!e.getValue().isTextual() || !CODE.matcher(e.getValue().textValue()).matches()) {
                problems.add("unavailableReasons." + e.getKey() + ": reason code must match " + CODE.pattern());
            } else {
                reasons.put(cause, e.getValue().textValue());
            }
        }
        for (UnavailableCause required : REQUIRED_CAUSES) {
            if (!reasons.containsKey(required) && node.get(required.name()) == null) {
                problems.add("unavailableReasons: missing " + required);
            }
        }
        return reasons;
    }

    // ------------------------------------------------------------------ JSON 도우미

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            throw new InvalidPolicyException(List.of("policy body is empty"));
        }
        try {
            JsonNode root = JSON.readTree(body);
            if (root == null || !root.isObject()) {
                throw new InvalidPolicyException(List.of("policy body must be a JSON object"));
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new InvalidPolicyException(List.of("policy body is not valid JSON: " + e.getOriginalMessage()));
        }
    }

    private static void onlyKeys(JsonNode node, Set<String> allowed, String at, List<String> problems) {
        if (node == null || !node.isObject()) {
            return;
        }
        node.properties().forEach(e -> {
            String k = e.getKey();
            if (!allowed.contains(k)) {
                problems.add(at + k + ": unknown key");
            }
        });
    }

    private static JsonNode object(JsonNode node, String key, List<String> problems) {
        JsonNode value = node.get(key);
        if (value == null || !value.isObject()) {
            problems.add(key + ": required object");
            return JSON.createObjectNode();
        }
        return value;
    }

    private static String text(JsonNode node, String key, List<String> problems) {
        return text(node, key, problems, "");
    }

    private static String text(JsonNode node, String key, List<String> problems, String at) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            problems.add(at + key + ": required non-blank string");
            return null;
        }
        return value.textValue();
    }

    private static int integer(JsonNode node, String key, int min, int max, List<String> problems) {
        return integer(node, key, min, max, problems, "");
    }

    private static int integer(JsonNode node, String key, int min, int max, List<String> problems, String at) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            problems.add(at + key + ": required integer");
            return min;
        }
        int v = value.intValue();
        if (v < min || v > max) {
            problems.add(at + key + ": must be within [" + min + ", " + max + "]");
        }
        return v;
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, String key, Class<E> type, List<String> problems) {
        JsonNode value = node.get(key);
        E parsed = value != null && value.isTextual() ? parseEnum(type, value.textValue()) : null;
        if (parsed == null) {
            problems.add(key + ": required, one of " + java.util.Arrays.toString(type.getEnumConstants()));
        }
        return parsed;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String name) {
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(name)) {
                return constant;
            }
        }
        return null;
    }
}
