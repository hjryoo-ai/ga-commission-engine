package ga.comm.outbox.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.InputStreamSource;
import com.networknt.schema.resource.SchemaLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 포털 계약 검증기(시험 전용, Phase E1 C1·C7·C9) — 엔진이 발행하는 이벤트와 피드 응답을 <b>포털이 소유한 계약</b>으로 검증한다.
 *
 * <p>스키마 IRI는 저장소 사본(클래스패스 {@code contracts/shared/**} — 이 모듈의 시험 픽스처 리소스)으로만 푼다:
 * {@code https://ga.example/portal/contracts/} → 포털 사본, {@code https://ga.example/contracts/} → ga-disclosure 사본(공유 Envelope 핵심).
 * 그 밖의 http(s) IRI(메타스키마 제외)는 {@link IllegalStateException} — 원격 조회 0을 구조로 보장한다(포털 {@code EventSchemaTest}와 같은 방식).
 * OpenAPI(YAML)는 JSON으로 바꿔 같은 IRI로 등록한다.
 */
public final class PortalContract {

    public static final String PORTAL = "https://ga.example/portal/contracts/";
    public static final String DISCLOSURE = "https://ga.example/contracts/";
    public static final String ENVELOPE = PORTAL + "events/v1/envelope.schema.json";
    public static final String CORE = DISCLOSURE + "events/v1/envelope-core.schema.json";
    public static final String FEED_API = PORTAL + "api/v1/engine-internal.openapi.yaml";
    private static final String PORTAL_COPY = "contracts/shared/ga-agent-portal/";
    private static final String DISCLOSURE_COPY = "contracts/shared/ga-disclosure/";
    private static final String META = "https://json-schema.org/";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /**
     * YAML 1.2 core 스키마의 불리언(true/false만) — OpenAPI 3.1은 YAML 1.2를 권장한다. 기본 YAML 1.1 해석은 {@code yes}/{@code no}/{@code on}/
     * {@code off}를 불리언으로 읽어 {@code fyCommissionByInstallment}의 {@code required: [no, amount]}가 {@code [false, amount]}가 된다(E2 ②에서 발견 —
     * 그 전에는 이 스키마를 검증에 쓰지 않았다).
     */
    private static final YAMLMapper YAML = YAMLMapper.builder().enable(YAMLParser.Feature.PARSE_BOOLEAN_LIKE_WORDS_AS_STRINGS).build();
    private static final PortalContract INSTANCE = new PortalContract();

    /** 저장소 사본에서 풀린 스키마 문서 수(시험이 0이 아님을 본다). */
    private final AtomicInteger resolved = new AtomicInteger();
    private final JsonSchemaFactory factory;

    private PortalContract() {
        SchemaLoader repositoryOnly = iri -> {
            String s = iri.toString();
            String resource;
            if (s.startsWith(PORTAL)) {
                resource = PORTAL_COPY + s.substring(PORTAL.length());
            } else if (s.startsWith(DISCLOSURE)) {
                resource = DISCLOSURE_COPY + s.substring(DISCLOSURE.length());
            } else if (s.startsWith(META) || !(s.startsWith("http:") || s.startsWith("https:"))) {
                return null;   // 메타스키마는 검증기에 내장, 그 밖의 스킴(classpath 등)은 기본 로더
            } else {
                throw new IllegalStateException("원격 스키마 조회 금지(E1 C9): " + s);
            }
            byte[] bytes = read(resource);
            resolved.incrementAndGet();
            byte[] json = resource.endsWith(".yaml") ? yamlToJson(bytes) : bytes;
            InputStreamSource source = () -> new ByteArrayInputStream(json);
            return source;
        };
        factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
                b -> b.schemaLoaders(loaders -> loaders.values(list -> list.add(0, repositoryOnly))));
    }

    public static PortalContract get() {
        return INSTANCE;
    }

    public JsonSchema schema(String iri) {
        return factory.getSchema(SchemaLocation.of(iri));
    }

    /** 포털 Envelope(allOf[핵심, 포털 제약 — type별 if/then payload, type↔kind 짝]). */
    public Set<ValidationMessage> validateEnvelope(JsonNode event) {
        return schema(ENVELOPE).validate(event);
    }

    public Set<ValidationMessage> validateCore(JsonNode event) {
        return schema(CORE).validate(event);
    }

    public Set<ValidationMessage> validatePayload(String type, JsonNode payload) {
        return schema(PORTAL + "events/v1/payloads/" + type + ".v1.schema.json").validate(payload);
    }

    /** OpenAPI {@code components.schemas.<name>}(FeedResponse·Problem). */
    public Set<ValidationMessage> validateApiSchema(String name, JsonNode body) {
        return schema(FEED_API + "#/components/schemas/" + name).validate(body);
    }

    public int resolvedDocuments() {
        return resolved.get();
    }

    /** 포털 사본 파일(클래스패스) — 샘플·스키마 원문을 시험이 읽을 때. */
    public static JsonNode portalFile(String path) {
        try {
            byte[] bytes = read(PORTAL_COPY + path);
            return path.endsWith(".yaml") ? YAML.readTree(bytes) : JSON.readTree(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 포털 샘플 17개의 {@code events/v1/samples/} 기준 상대 경로 — 포털 계약 v1.2부터 디렉터리를 포함한다({@code a1/}·{@code contract-only/}·
     * {@code standalone/}). 파일 이름(= seq) 순.
     */
    public static List<String> sampleNames() {
        String text = new String(read(PORTAL_COPY + "UPSTREAM"), StandardCharsets.UTF_8);
        return text.lines().skip(1).map(String::trim)
                .filter(l -> l.startsWith("events/v1/samples/"))
                .map(l -> l.substring("events/v1/samples/".length()))
                .sorted(java.util.Comparator.comparing(PortalContract::fileName)).toList();
    }

    /** 샘플 이벤트 하나 — {@code id}는 파일 상대 경로(JSONL이면 {@code #줄번호}를 붙인다). */
    public record Sample(String id, JsonNode doc) {
    }

    /**
     * 샘플 이벤트 전부 — 파일 이름 순, JSONL(포털 계약 v1.4 {@code a1/a1_portal_150000_e2.jsonl} — 엔진 E2 ⓪ 둘째 판 바이트)은 줄마다 하나. 포털 v1.3 기준
     * 17건(a1 10 + contract-only 3 + standalone 4), 13종.
     */
    public static List<Sample> sampleEvents() {
        List<Sample> out = new java.util.ArrayList<>();
        for (String name : sampleNames()) {
            if (name.endsWith(".jsonl")) {
                String text = new String(read(PORTAL_COPY + "events/v1/samples/" + name), StandardCharsets.UTF_8);
                List<String> lines = text.lines().filter(l -> !l.isBlank()).toList();
                for (int i = 0; i < lines.size(); i++) {
                    try {
                        out.add(new Sample(name + "#" + (i + 1), JSON.readTree(lines.get(i))));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            } else {
                out.add(new Sample(name, portalFile("events/v1/samples/" + name)));
            }
        }
        return out;
    }

    /** 원문 바이트(포털 사본) — A-1 바이트 대조용. */
    public static byte[] portalBytes(String path) {
        return read(PORTAL_COPY + path);
    }

    /** 파일 이름({@code 02-CommissionCalculated.json})의 샘플 상대 경로(어느 디렉터리에 있든). */
    public static String samplePath(String fileName) {
        return sampleNames().stream().filter(n -> fileName(n).equals(fileName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("포털 샘플 없음: " + fileName));
    }

    private static String fileName(String relative) {
        return relative.substring(relative.lastIndexOf('/') + 1);
    }

    private static byte[] read(String resource) {
        try (InputStream in = PortalContract.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("계약 사본이 클래스패스에 없다: " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] yamlToJson(byte[] yaml) {
        try {
            return JSON.writeValueAsBytes(YAML.readTree(yaml));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
