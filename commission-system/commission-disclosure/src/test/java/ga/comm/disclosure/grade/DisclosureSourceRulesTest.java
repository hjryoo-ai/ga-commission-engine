package ga.comm.disclosure.grade;

import com.fasterxml.jackson.databind.JsonNode;
import ga.comm.disclosure.grade.fixture.PolicyFixtures;
import ga.comm.disclosure.grade.snapshot.SnapshotJson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 소스 규칙(E1 "소스 diff 0"의 뒷받침 + 부록 B-1): 비교설명 경로의 프로덕션 소스를 스캔한다.
 * <ol>
 *   <li>정책 픽스처의 임계치·등급 코드·라벨·사유 코드가 <b>문자열 리터럴로</b> 어느 프로덕션 소스에도 없다(데이터에만 있다).</li>
 *   <li>{@code double}/{@code float} 없음.</li>
 *   <li>{@code divide(}·{@code setScale(}은 {@link RatioToAvg} 한 곳뿐(중앙 반올림).</li>
 * </ol>
 * 스캔 범위: commission-disclosure·commission-api·commission-infra·commission-app의 src/main 전부.
 */
class DisclosureSourceRulesTest {

    private static final Path ROOT = Path.of(System.getProperty("engine.contractsDir")).getParent();
    private static final List<String> MODULES = List.of("commission-disclosure", "commission-api", "commission-infra", "commission-app");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static List<Path> sources(List<String> modules) {
        List<Path> files = new ArrayList<>();
        for (String m : modules) {
            Path main = ROOT.resolve(m).resolve("src/main");
            if (!Files.isDirectory(main)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(main)) {
                walk.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".sql") || p.toString().endsWith(".yml"))
                        .forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(files).as("scanned sources").isNotEmpty();
        return files;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 정책 픽스처에서 데이터로만 있어야 하는 값들. */
    private static Set<String> policyValues() throws IOException {
        Set<String> values = new LinkedHashSet<>();
        for (String fixture : List.of(PolicyFixtures.GRADING_5, PolicyFixtures.GRADING_4)) {
            JsonNode policy = SnapshotJson.mapper().readTree(PolicyFixtures.read(fixture));
            for (JsonNode g : policy.get("grades")) {
                values.add(g.get("code").asText());
                values.add(g.get("label").asText());
                for (String bound : List.of("minRatio", "maxRatio")) {
                    if (g.has(bound)) {
                        values.add(g.get(bound).asText());
                    }
                }
            }
            policy.get("unavailableReasons").forEach(r -> values.add(r.asText()));
        }
        return values;
    }

    @Test
    void 임계치_라벨_등급코드_사유코드는_프로덕션_소스의_문자열_리터럴에_없다() throws IOException {
        Set<String> forbidden = policyValues();
        assertThat(forbidden).contains("1.30", "VERY_HIGH", "매우높음", "NO_RATE_DATA");
        List<String> hits = new ArrayList<>();
        for (Path p : sources(MODULES)) {
            Matcher m = STRING_LITERAL.matcher(read(p));
            while (m.find()) {
                if (forbidden.contains(m.group(1))) {
                    hits.add(ROOT.relativize(p) + ": \"" + m.group(1) + "\"");
                }
            }
        }
        assertThat(hits).isEmpty();
    }

    @Test
    void 비교설명_모듈에_double_float가_없다() {
        Pattern floating = Pattern.compile("\\b(double|float|Double|Float)\\b");
        List<String> hits = sources(List.of("commission-disclosure")).stream()
                .filter(p -> floating.matcher(read(p).replaceAll("//.*|/\\*(?s:.*?)\\*/", "")).find())
                .map(p -> ROOT.relativize(p).toString()).toList();
        assertThat(hits).isEmpty();
    }

    @Test
    void 나눗셈과_반올림은_RatioToAvg_한_곳뿐() {
        Pattern rounding = Pattern.compile("\\.(divide|setScale|round)\\(");
        List<String> hits = sources(List.of("commission-disclosure")).stream()
                .filter(p -> rounding.matcher(read(p)).find())
                .map(p -> p.getFileName().toString()).toList();
        assertThat(hits).containsExactly("RatioToAvg.java");
    }
}
