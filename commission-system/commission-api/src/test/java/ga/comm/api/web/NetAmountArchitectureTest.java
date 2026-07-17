package ga.comm.api.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 아키텍처 테스트 (설계서 §3.0 합산 규약, 부록 B-4) — 순액 산출이 컨트롤러·쿼리에서 상태 필터
 * SUM으로 새지 않음을 구조적으로 고정한다. 서드파티 의존 없이 소스를 스캔한다(공급망 표면 0).
 *
 * <p>규칙: commission-api 본문(src/main)에서 {@code calcAmount}를 <b>합산</b>(reduce/sum/summing)하는
 * 코드는 금지된다 — 순액은 반드시 {@code NetAmountCalculator}(commission-calc)를 경유해야 한다.
 * 개별 레코드 표시용 {@code r.calcAmount()} 매핑(합산 아님)은 허용된다.
 */
class NetAmountArchitectureTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final List<String> REDUCTION_TOKENS =
            List.of("reduce(", ".sum(", "summing", "Collectors.reducing");

    @Test
    void 순액은_컨트롤러_쿼리에서_직접_합산되지_않는다() {
        assertThat(Files.exists(MAIN))
                .as("소스 루트를 찾지 못함(테스트 cwd = 모듈 디렉터리 가정): " + MAIN.toAbsolutePath())
                .isTrue();

        List<String> violations = javaFiles().filter(NetAmountArchitectureTest::sumsCalcAmount)
                .map(Path::toString).toList();

        assertThat(violations)
                .as("commission-api 본문에서 calcAmount를 직접 합산함 — NetAmountCalculator 경유 필요(§3.0)")
                .isEmpty();
    }

    @Test
    void 조회_파사드는_NetAmountCalculator를_경유한다() {
        Path facade = MAIN.resolve("ga/comm/api/CommissionQueryService.java");
        assertThat(read(facade)).contains("NetAmountCalculator");
    }

    private static boolean sumsCalcAmount(Path file) {
        String src = read(file);
        if (!src.contains("calcAmount")) {
            return false;
        }
        return REDUCTION_TOKENS.stream().anyMatch(src::contains);
    }

    private static Stream<Path> javaFiles() {
        try {
            return Files.walk(MAIN).filter(p -> p.toString().endsWith(".java"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
