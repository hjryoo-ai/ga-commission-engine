package ga.comm.golden;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 골든셋 CSV 러너 (Phase 14, 설계서 §8.1). {@code golden/cases/} 아래의 각 케이스 디렉터리를
 * 스캔해 파라미터라이즈드로 실행한다 — <b>CSV를 추가하는 것만으로</b> 새 케이스가 CI에 편입된다(코드 변경 0).
 *
 * <p>불일치는 정산 담당자가 읽는 형태로 출력하고, 하나라도 있으면 빌드를 실패시킨다(CI 상시 편성).
 * 이 러너의 산출물은 테스트 코드가 아니라 "정산 담당자와 시스템 간 계약"인 CSV다 — 작성 규약은
 * {@code src/test/resources/golden/README.md}.
 */
class GoldenCsvRunnerTest {

    @TestFactory
    Stream<DynamicTest> 골든셋() throws Exception {
        List<Path> caseDirs = discoverCaseDirs();
        printSummary(caseDirs);
        if (caseDirs.isEmpty()) {
            return Stream.of(DynamicTest.dynamicTest("골든셋_케이스_존재",
                    () -> fail("golden/cases 아래에 케이스가 없습니다")));
        }
        return caseDirs.stream().map(dir -> DynamicTest.dynamicTest(dir.getFileName().toString(),
                () -> runCase(dir)));
    }

    private void runCase(Path dir) {
        GoldenCase gc = GoldenCase.load(dir);
        GoldenEngine engine = new GoldenEngine(gc);
        engine.run();
        List<String> diffs = new GoldenComparator(gc, engine).mismatches();
        if (!diffs.isEmpty()) {
            fail("골든 케이스 불일치 [" + gc.id + "] " + gc.title + " (축: " + gc.axis + ")\n  "
                    + String.join("\n  ", diffs));
        }
    }

    /** 케이스 수·커버 축 요약을 빌드 로그에 출력한다(완료 기준). */
    private void printSummary(List<Path> caseDirs) {
        TreeMap<String, Integer> byAxis = new TreeMap<>();
        for (Path dir : caseDirs) {
            String axis = GoldenCsv.readKeyValue(dir.resolve("case.csv")).getOrDefault("axis", "(미지정)");
            for (String a : axis.split(";")) {
                byAxis.merge(a.trim().isEmpty() ? "(미지정)" : a.trim(), 1, Integer::sum);
            }
        }
        String axes = byAxis.entrySet().stream()
                .map(e -> e.getKey() + "×" + e.getValue()).collect(Collectors.joining(", "));
        System.out.println("[골든셋] 케이스 " + caseDirs.size() + "건 — 커버 축: " + axes);
    }

    private List<Path> discoverCaseDirs() throws URISyntaxException, IOException {
        URL url = getClass().getResource("/golden/cases");
        if (url == null) {
            return List.of();
        }
        Path root = Paths.get(url.toURI());
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("_")) // _TEMPLATE 등 제외
                    .filter(p -> Files.exists(p.resolve("case.csv")))
                    .sorted()
                    .forEach(dirs::add);
        }
        return dirs;
    }

    /** 러너 자체 스모크 — 케이스가 실제로 하나 이상 편성됐는지 확인(빈 스캔 회귀 방지). */
    @org.junit.jupiter.api.Test
    void 골든셋은_비어있지_않다() throws Exception {
        assertTrue(discoverCaseDirs().size() >= 1, "golden/cases 스캔 결과가 비어 있습니다");
    }
}
