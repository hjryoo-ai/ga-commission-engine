package ga.comm.disclosure.grade.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E3.1 §3-6: 엔진 JCS({@link Jcs})가 ga-disclosure {@code platform-canonical}과 <b>같은 벡터에서 같은 바이트</b>를 낸다.
 * 벡터 파일({@code src/test/resources/jcs/})은 ga-disclosure@9379be96 {@code platform-canonical/src/test/resources/jcs/}의
 * 복사본(34개 파일, 출처는 {@code jcs/SOURCES.txt})이고, 검사 항목은 저쪽 {@code CanonicalizerTest}와 같다:
 * 파일 벡터 8종의 정규 바이트, 공개 UTF-8 hex 7종, RFC 8785 §3.2.3 정렬 순서, 부록 B 숫자 26행, ES6 숫자 표본 1,000행,
 * RFC MUST 거부 6종. 숫자 벡터의 입력 텍스트는 저쪽과 같은 방식(IEEE 비트 → {@code Double.toString})으로 만든다(테스트 전용 —
 * 엔진 금액 계산의 double 금지와 무관, 정규화 대상은 JSON 숫자 텍스트다).
 */
class JcsCrossCheckTest {

    private static String resource(String path) {
        try (InputStream in = JcsCrossCheckTest.class.getResourceAsStream("/jcs/" + path)) {
            if (in == null) {
                throw new IllegalStateException("missing vector " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] hex(String spaced) {
        return HexFormat.of().parseHex(spaced.replaceAll("\\s+", ""));
    }

    static Stream<String> fileVectors() {
        return Stream.of("arrays", "french", "structures", "unicode", "values", "weird", "rfc8785-3.2.2", "rfc8785-3.2.3-sorting");
    }

    @ParameterizedTest
    @MethodSource("fileVectors")
    void 파일_벡터는_기대_바이트로_정규화된다(String name) {
        byte[] actual = Jcs.canonicalizeUtf8(resource("input/" + name + ".json"));
        assertThat(new String(actual, StandardCharsets.UTF_8)).isEqualTo(resource("output/" + name + ".json"));
        assertThat(actual).isEqualTo(resource("output/" + name + ".json").getBytes(StandardCharsets.UTF_8));
    }

    static Stream<String> hexVectors() {
        return Stream.of("arrays", "french", "structures", "unicode", "values", "weird", "rfc8785-3.2.2");
    }

    @ParameterizedTest
    @MethodSource("hexVectors")
    void 공개된_UTF8_hex와_같다(String name) {
        assertThat(Jcs.canonicalizeUtf8(resource("input/" + name + ".json"))).isEqualTo(hex(resource("outhex/" + name + ".txt")));
    }

    @Test
    void 속성_정렬은_RFC_순서를_따른다() {
        String canonical = Jcs.canonicalize(resource("input/rfc8785-3.2.3-sorting.json"));
        List<String> expectedOrder = resource("rfc8785-3.2.3-sorting.order.txt").lines().toList();
        int from = 0;
        for (String value : expectedOrder) {
            int at = canonical.indexOf(value, from);
            assertThat(at).as("%s after %d in %s", value, from, canonical).isGreaterThanOrEqualTo(from);
            from = at + value.length();
        }
        assertThat(expectedOrder).hasSize(7);
    }

    static Stream<Arguments> appendixBNumbers() {
        return resource("numbers-rfc8785-appendix-b.txt").lines()
                .filter(l -> !l.isBlank() && !l.startsWith("#"))
                .map(l -> l.split(",", 2))
                .map(p -> Arguments.of(p[0], p[1]));
    }

    private static String numberText(String ieeeHex) {
        return "[" + Double.toString(Double.longBitsToDouble(Long.parseUnsignedLong(ieeeHex, 16))) + "]";
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("appendixBNumbers")
    void 부록_B_숫자_직렬화(String ieeeHex, String expected) {
        String text = numberText(ieeeHex);
        if (expected.equals("ERROR")) {
            assertThatThrownBy(() -> Jcs.canonicalize(text)).isInstanceOf(IllegalArgumentException.class);
        } else {
            assertThat(Jcs.canonicalize(text)).isEqualTo("[" + expected + "]");
        }
    }

    @Test
    void 부록_B는_26행이다() {
        assertThat(appendixBNumbers()).hasSize(26);
    }

    static Stream<Arguments> es6Sample() {
        return resource("es6testfile-1k.txt").lines().map(l -> l.split(",", 2)).map(p -> Arguments.of(p[0], p[1]));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("es6Sample")
    void ES6_숫자_표본(String ieeeHex, String expected) {
        assertThat(Jcs.canonicalize(numberText(ieeeHex))).isEqualTo("[" + expected + "]");
    }

    @Test
    void ES6_표본은_1000행이다() {
        assertThat(es6Sample()).hasSize(1000);
    }

    static Stream<String> invalidVectors() {
        return Stream.of("duplicate-property", "lone-surrogate-high", "lone-surrogate-in-key", "lone-surrogate-low",
                "number-overflow-infinity", "number-overflow-neg-infinity");
    }

    @ParameterizedTest
    @MethodSource("invalidVectors")
    void RFC_MUST_위반은_거부된다(String name) {
        assertThatThrownBy(() -> Jcs.canonicalize(resource("invalid/" + name + ".json"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 짝이_맞는_서로게이트는_정상_인코딩() {
        assertThat(Jcs.canonicalizeUtf8("[\"\\ud83d\\ude00\"]")).isEqualTo("[\"😀\"]".getBytes(StandardCharsets.UTF_8));
    }
}
