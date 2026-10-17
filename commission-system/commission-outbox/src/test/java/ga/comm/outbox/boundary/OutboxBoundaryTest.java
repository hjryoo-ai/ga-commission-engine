package ga.comm.outbox.boundary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모듈 경계(E1 Q4/P9 — ArchUnit을 들이지 않고 원문 스캔). 역방향 의존(업무 모듈 → 아웃박스)은 Gradle이 순환으로 이미 거부한다(주입 I12);
 * 이 시험은 그 밖의 두 규칙과 아웃박스의 Spring 무의존을 본다. 읽는 원천은 전부 이 태스크의 Gradle 입력이다(build.gradle.kts).
 */
class OutboxBoundaryTest {

    /** 업무 모듈 — 아웃박스를 모른다. */
    private static final List<String> BUSINESS = List.of("commission-domain", "commission-rule", "commission-calc", "commission-limit",
            "commission-deferral", "commission-clawback", "commission-inbound", "commission-recon", "commission-shadow",
            "commission-settlement", "commission-disclosure");
    /** project(":commission-outbox")를 참조해도 되는 모듈(E1 승인 Q4) + 로컬 스택 실행기(E2 ⑤ — testFixtures의 시나리오·단계 시계, 운영 jar 밖). */
    private static final List<String> ALLOWED = List.of("commission-infra", "commission-api", "commission-batch", "commission-app",
            "commission-localstack");
    private static final List<String> ALL = List.of("commission-domain", "commission-rule", "commission-calc", "commission-limit",
            "commission-deferral", "commission-clawback", "commission-inbound", "commission-recon", "commission-shadow",
            "commission-settlement", "commission-disclosure", "commission-outbox", "commission-batch", "commission-api",
            "commission-infra", "commission-app", "commission-localstack");
    private static final Pattern OUTBOX_IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?ga\\.comm\\.outbox\\b|\\bga\\.comm\\.outbox\\.");
    private static final Pattern SPRING_IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?org\\.springframework\\.");

    private static Path root() {
        String dir = System.getProperty("engine.rootDir");
        assertThat(dir).as("engine.rootDir(Gradle이 준다)").isNotNull();
        return Path.of(dir);
    }

    private static List<Path> javaFiles(String module) {
        Path main = root().resolve(module).resolve("src/main/java");
        assertThat(main).as(module + " main").isDirectory();
        try (Stream<Path> s = Files.walk(main)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void 업무_모듈_main은_아웃박스를_모른다() {
        int scanned = 0;
        for (String m : BUSINESS) {
            for (Path f : javaFiles(m)) {
                scanned++;
                assertThat(OUTBOX_IMPORT.matcher(read(f)).find()).as(root().relativize(f).toString()).isFalse();
            }
        }
        assertThat(scanned).isGreaterThan(100);
    }

    @Test
    void 아웃박스를_참조하는_빌드_파일은_infra_api_batch_app뿐() {
        List<String> referencing = ALL.stream()
                .filter(m -> !m.equals("commission-outbox"))
                .filter(m -> read(root().resolve(m).resolve("build.gradle.kts")).contains("project(\":commission-outbox\")"))
                .toList();
        assertThat(referencing).containsExactlyInAnyOrderElementsOf(ALLOWED);
    }

    @Test
    void 아웃박스_main은_Spring을_모른다() {
        List<Path> files = javaFiles("commission-outbox");
        assertThat(files).isNotEmpty();
        assertThat(files).allSatisfy(f -> assertThat(SPRING_IMPORT.matcher(read(f)).find()).as(f.toString()).isFalse());
    }
}
