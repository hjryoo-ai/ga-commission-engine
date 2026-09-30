package ga.comm.disclosure.grade.fixture;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** 정책 body 픽스처(테스트 리소스 disclosure-policies/*.json). 임계치·라벨·사유 코드는 이 데이터 파일에만 있다. */
public final class PolicyFixtures {

    public static final String GRADING_5 = "grading-5-step.json";
    public static final String GRADING_4 = "grading-4-step.json";
    public static final String RANKING_SHARED = "ranking-shared.json";
    public static final String RANKING_STRICT = "ranking-strict.json";

    private PolicyFixtures() {
    }

    public static String read(String name) {
        try (InputStream in = PolicyFixtures.class.getResourceAsStream("/disclosure-policies/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("no policy fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
