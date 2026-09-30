package ga.comm.disclosure.grade.snapshot;

import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * RFC 8785 JCS 진입점(엔진의 유일한 정규화 경로). 스냅샷 응답 원문({@link SnapshotJson#canonical})이 이것을 거친다.
 * ga-disclosure {@code platform-canonical}과 같은 바이트를 내는지는 두 저장소가 공유하는 테스트 벡터로 확인한다
 * ({@code JcsCrossCheckTest}, E3.1 §3-6) — 다르면 봉인 해시 대사가 깨진다.
 */
public final class Jcs {

    private Jcs() {
    }

    /**
     * JSON 텍스트 → 정규 문자열. 정규화할 수 없는 입력(RFC 8785 MUST 위반)은 {@link IllegalArgumentException}.
     * 라이브러리(erdtman 1.1)는 중복 키·표현 불가 숫자는 거부하지만 <b>짝 없는 서로게이트</b>(§3.2.2.2 MUST 오류)는 통과시킨다 —
     * 그대로 UTF-8로 바꾸면 '?'로 조용히 치환되므로 결과를 검사해 거부한다(E3.1 교차 검증에서 발견).
     */
    public static String canonicalize(String json) {
        String canonical;
        try {
            canonical = new JsonCanonicalizer(json).getEncodedString();
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("not canonicalizable JSON (RFC 8785)", e);
        }
        rejectLoneSurrogates(canonical);
        return canonical;
    }

    private static void rejectLoneSurrogates(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new IllegalArgumentException("lone surrogate at index " + i + " (RFC 8785 §3.2.2.2)");
            }
        }
    }

    /** 정규 문자열의 UTF-8 바이트(해시 입력). */
    public static byte[] canonicalizeUtf8(String json) {
        return canonicalize(json).getBytes(StandardCharsets.UTF_8);
    }
}
