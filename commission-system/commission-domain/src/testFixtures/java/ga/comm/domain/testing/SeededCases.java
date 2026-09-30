package ga.comm.domain.testing;

import org.junit.jupiter.params.provider.Arguments;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * 시드 고정 속성 테스트 케이스 생성기 (JUnit 5 {@code @ParameterizedTest} + {@code @MethodSource}용).
 *
 * <p>jqwik을 대체한다(Phase E3-0 — jqwik 1.10은 AI 에이전트 사용 배제 조항과 출력 삽입 지시문을 가진 버전이며,
 * 빌드가 {@code net.jqwik} 해석을 차단한다). {@code ga-disclosure}의 {@code platform-core} 테스트 픽스처
 * {@code SeededCases}를 Java 21로 옮긴 것이다. 규약:
 * <ul>
 *   <li>시드는 테스트 소스에 상수로 적는다. 같은 시드 → 같은 케이스 순서·값({@value #ALGORITHM}).</li>
 *   <li>각 케이스는 {@link Arguments#argumentSet(String, Object...)}로 만들어 표시 이름이 {@code seed=0x…, case=N}이 된다.
 *       실패 보고에 시드와 인덱스가 그대로 찍혀 재현이 가능하다.</li>
 *   <li>{@link #withEdges}로 경계값 케이스(jqwik의 edge case에 해당)를 무작위 케이스 앞에 명시적으로 붙인다.</li>
 *   <li>로컬 탐색용으로 환경변수 {@value #SEED_OVERRIDE_ENV}(10진 또는 {@code 0x} 16진)로 시드를 덮어쓸 수 있다.
 *       단 {@code CI=true} 환경에서는 덮어쓰기를 무시하고 소스 상수만 쓴다.</li>
 * </ul>
 */
public final class SeededCases {

    /** jqwik {@code @Property}의 기본 시행 수(1000)와 같다 — 교체로 시행 수가 줄지 않게. */
    public static final int DEFAULT_COUNT = 1000;
    public static final String ALGORITHM = "L64X128MixRandom";
    public static final String SEED_OVERRIDE_ENV = "GA_SEEDED_CASES_SEED";

    private SeededCases() {
    }

    public static Stream<Arguments> of(long seed, Function<RandomGenerator, Object[]> generator) {
        return of(seed, DEFAULT_COUNT, generator);
    }

    public static Stream<Arguments> of(long seed, int count, Function<RandomGenerator, Object[]> generator) {
        return withEdges(seed, count, List.of(), generator);
    }

    /** 경계값 케이스({@code edge=N})를 먼저, 그다음 무작위 {@code count}개({@code case=N}). */
    public static Stream<Arguments> withEdges(long seed, int count, List<Object[]> edges,
                                              Function<RandomGenerator, Object[]> generator) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        long effective = effectiveSeed(seed);
        RandomGenerator random = generator(effective);
        String prefix = "seed=0x" + Long.toHexString(effective).toUpperCase(Locale.ROOT);
        List<Arguments> cases = new ArrayList<>(edges.size() + count);
        for (int i = 0; i < edges.size(); i++) {
            cases.add(Arguments.argumentSet(prefix + ", edge=" + i, edges.get(i)));
        }
        // 순차 생성: 케이스 i의 값은 시드와 i에 의해 결정된다(앞선 케이스를 같은 순서로 생성하므로).
        IntStream.range(0, count).forEach(i -> cases.add(Arguments.argumentSet(prefix + ", case=" + i, generator.apply(random))));
        return cases.stream();
    }

    /** 소스 상수 시드에 환경변수 덮어쓰기 규칙을 적용한 실제 시드. */
    public static long effectiveSeed(long sourceSeed) {
        if ("true".equalsIgnoreCase(System.getenv("CI"))) {
            return sourceSeed;
        }
        String override = System.getenv(SEED_OVERRIDE_ENV);
        if (override == null || override.isBlank()) {
            return sourceSeed;
        }
        String s = override.strip();
        return s.startsWith("0x") || s.startsWith("0X") ? Long.parseUnsignedLong(s.substring(2), 16) : Long.parseLong(s);
    }

    public static RandomGenerator generator(long seed) {
        return RandomGeneratorFactory.of(ALGORITHM).create(seed);
    }

    /** [min, max] 닫힌 구간의 long. */
    public static long longIn(RandomGenerator r, long min, long max) {
        return max == Long.MAX_VALUE ? r.nextLong(min - 1, max) + 1 : r.nextLong(min, max + 1);
    }
}
