package ga.comm.localstack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 로컬 스택 피드 대조(E2 ⑤·C8) — 떠 있는 엔진의 {@code GET /internal/v1/events}를 {@code afterSeq=0}부터 끝까지 받아 이벤트마다 한 줄(Jackson
 * 압축 직렬화 = 엔진 {@code EnvelopeJson} 바이트)로 이어 붙이고, 두 번째 판 파일과 <b>바이트로</b> 비교한다. 다르면 첫 차이 줄 번호와 두 줄을 보이고
 * 종료 코드 1. 토큰은 파일({@code localstack/.state/engine-token})에서만 읽고 출력하지 않는다.
 *
 * <p>인자: 기대 파일 경로. 시스템 속성 {@code engine.url}(기본 {@code http://localhost:8080}), {@code engine.tokenFile}(기본
 * {@code localstack/.state/engine-token}, 작업 디렉터리 기준).
 */
public final class LocalStackVerify {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LocalStackVerify() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: LocalStackVerify <expected.jsonl>");
        }
        String base = System.getProperty("engine.url", "http://localhost:8080");
        String token = Files.readString(Path.of(System.getProperty("engine.tokenFile", "localstack/.state/engine-token")),
                StandardCharsets.UTF_8).strip();
        HttpClient http = HttpClient.newHttpClient();
        List<String> lines = new ArrayList<>();
        long after = 0;
        while (true) {
            HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create(base + "/internal/v1/events?afterSeq=" + after + "&limit=1000"))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                System.err.println("[verify] 피드 응답 " + res.statusCode() + ": " + new String(res.body(), StandardCharsets.UTF_8));
                System.exit(1);
            }
            JsonNode page = JSON.readTree(res.body());
            for (JsonNode e : page.path("events")) {
                lines.add(e.toString());
            }
            long next = page.path("nextSeq").asLong();
            if (page.path("events").isEmpty() || next >= page.path("headSeq").asLong()) {
                break;
            }
            after = next;
        }
        String actual = lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
        String expected = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        if (actual.equals(expected)) {
            System.out.println("[verify] 피드 = " + Path.of(args[0]).getFileName() + " (" + lines.size() + "건, 바이트 동일)");
            return;
        }
        String[] a = actual.split("\n", -1);
        String[] x = expected.split("\n", -1);
        for (int i = 0; i < Math.max(a.length, x.length); i++) {
            String ai = i < a.length ? a[i] : "<없음>";
            String xi = i < x.length ? x[i] : "<없음>";
            if (!ai.equals(xi)) {
                System.err.println("[verify] " + (i + 1) + "번째 줄이 다르다(피드 " + lines.size() + "건, 파일 " + (x.length - 1) + "건)");
                System.err.println("  피드: " + ai);
                System.err.println("  파일: " + xi);
                break;
            }
        }
        System.exit(1);
    }
}
