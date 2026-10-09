package ga.comm.infra.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3.2: 마이그레이션 번호는 공통·Oracle 전용 디렉터리를 합친 하나의 번호 공간에서 단조 증가하고, 빈 번호는 {@code docs/db-migrations.md}의
 * 빈 번호 표에 적힌 것뿐이다(표와 실제가 양방향으로 같다). 새 파일이 최댓값 + 1이 아니면(번호를 건너뛰거나 옛 빈 번호를 채우면) 실패한다.
 */
class MigrationNumberingTest {

    private static final Path DB = Path.of("src/main/resources/db");
    private static final List<Path> LOCATIONS = List.of(DB.resolve("migration"), DB.resolve("vendor/oracle"));
    private static final Path DOC = Path.of("../docs/db-migrations.md");
    private static final Pattern NAME = Pattern.compile("^V([1-9][0-9]*)__[a-z0-9]+(_[a-z0-9]+)*\\.sql$");
    private static final Pattern GAP_ROW = Pattern.compile("^\\| V([0-9]+)–V([0-9]+) \\| .+ \\|$");

    /** 번호 → 파일(두 디렉터리 합). 이름 규칙 위반·중복 번호는 여기서 실패. */
    static Map<Integer, Path> versions() throws IOException {
        Map<Integer, Path> versions = new TreeMap<>();
        for (Path dir : LOCATIONS) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.sorted().toList()) {
                    String name = f.getFileName().toString();
                    Matcher m = NAME.matcher(name);
                    assertThat(m.matches()).as("migration file name %s", f).isTrue();
                    Path previous = versions.put(Integer.parseInt(m.group(1)), f);
                    assertThat(previous).as("version V%s reused by %s", m.group(1), f).isNull();
                }
            }
        }
        return versions;
    }

    /** 문서의 빈 번호 표(표지 주석 사이). */
    static TreeSet<Integer> documentedGaps() throws IOException {
        String doc = Files.readString(DOC);
        String block = doc.substring(doc.indexOf("<!-- numbering:gaps"), doc.indexOf("<!-- /numbering:gaps -->"));
        TreeSet<Integer> gaps = new TreeSet<>();
        int rows = 0;
        for (String line : block.lines().toList()) {
            Matcher m = GAP_ROW.matcher(line);
            if (m.matches()) {
                rows++;
                for (int v = Integer.parseInt(m.group(1)); v <= Integer.parseInt(m.group(2)); v++) {
                    assertThat(gaps.add(v)).as("gap V%s listed twice", v).isTrue();
                }
            }
        }
        assertThat(rows).as("gap table rows").isPositive();
        return gaps;
    }

    @Test
    void 번호는_두_디렉터리를_합쳐_유일하고_이름_규칙을_따른다() throws IOException {
        Map<Integer, Path> versions = versions();
        assertThat(versions).isNotEmpty();
        assertThat(versions.keySet()).first().isEqualTo(1);
    }

    @Test
    void 빈_번호는_문서의_표와_정확히_같다() throws IOException {
        TreeSet<Integer> versions = new TreeSet<>(versions().keySet());
        List<Integer> actualGaps = new ArrayList<>();
        for (int v = 1; v < versions.last(); v++) {
            if (!versions.contains(v)) {
                actualGaps.add(v);
            }
        }
        assertThat(actualGaps).as("missing versions below V%s", versions.last()).containsExactlyElementsOf(documentedGaps());
    }

    @Test
    void 문서의_빈_번호_뒤로는_연속이다_새_번호는_최댓값_다음뿐() throws IOException {
        TreeSet<Integer> versions = new TreeSet<>(versions().keySet());
        int lastGap = documentedGaps().last();
        List<Integer> after = new ArrayList<>(versions.tailSet(lastGap, false));
        assertThat(after).as("versions after the last documented gap").isNotEmpty();
        for (int i = 0; i < after.size(); i++) {
            assertThat(after.get(i)).as("contiguous from V%s", lastGap + 1).isEqualTo(lastGap + 1 + i);
        }
        assertThat(versions.last()).as("V104 (E3.2) is the first number under the single numbering space").isGreaterThanOrEqualTo(104);
    }
}
