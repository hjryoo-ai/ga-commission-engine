package ga.comm.outbox.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E1 2단계(§5, 승인 §2 "검증기 판정 일치"): 엔진 쪽 검증기(networknt 1.5.9)가 포털 계약 사본에 대해 포털 {@code EventSchemaTest}
 * (networknt 3.0.6, 포털 {@code f54d008})와 <b>같은 판정</b>을 내린다 — 샘플 17개 통과, 필수 필드 하나씩 뺀 변형 거부, 핵심 위반 거부,
 * {@code type}↔{@code kind}·payload 짝, 열거 밖 {@code type}·{@code version} 거부, 모르는 필드 허용, 소수는 문자열, v1.1·v1.1.1 선택 필드,
 * 피드 응답 {@code tenantId}·정수 커서·Problem. 포털 시험의 변형을 그대로 옮겼고 기대 결과도 포털과 같다(다르면 이 시험이 실패하고
 * 어느 쪽이 2020-12에 맞는지 보고한다 — 엔진 검증기를 올리지 않는다).
 */
class PortalContractAgreementTest {

    private static final PortalContract C = PortalContract.get();

    static final List<String> EVENT_TYPES = List.of(
            "AgentSnapshot", "CommissionCalculated", "AdjustmentPosted", "LimitLedgerOpened", "LimitLedgerPosted",
            "LimitLedgerRebased", "DeferralScheduled", "DeferralStatusChanged", "ClawbackReceivableUpdated",
            "PolicyEventObserved", "StatementEstimated", "StatementConfirmed", "PayoutCompleted");

    static final Map<String, String> KINDS = Map.ofEntries(
            Map.entry("AgentSnapshot", "AGENT"), Map.entry("CommissionCalculated", "COMM_CALC"),
            Map.entry("AdjustmentPosted", "ADJUSTMENT"), Map.entry("LimitLedgerOpened", "LIMIT_LEDGER"),
            Map.entry("LimitLedgerPosted", "LIMIT_LEDGER"), Map.entry("LimitLedgerRebased", "LIMIT_LEDGER"),
            Map.entry("DeferralScheduled", "DEFERRAL_SCHEDULE"), Map.entry("DeferralStatusChanged", "DEFERRAL_SCHEDULE"),
            Map.entry("ClawbackReceivableUpdated", "CLAWBACK_RECEIVABLE"), Map.entry("PolicyEventObserved", "POLICY"),
            Map.entry("StatementEstimated", "STATEMENT"), Map.entry("StatementConfirmed", "STATEMENT"), Map.entry("PayoutCompleted", "PAYOUT"));

    private static ObjectNode sample(String name) {
        return (ObjectNode) PortalContract.portalFile("events/v1/samples/" + name).deepCopy();
    }

    private static JsonNode payloadSchema(String type) {
        return PortalContract.portalFile("events/v1/payloads/" + type + ".v1.schema.json");
    }

    static Stream<Arguments> samples() {
        return PortalContract.sampleNames().stream().map(n -> Arguments.argumentSet(n, n));
    }

    // ------------------------------------------------------------------ 샘플

    @Test
    void samplesCoverThe13Types() {
        List<String> names = PortalContract.sampleNames();
        assertThat(names).hasSize(17);
        assertThat(names.stream().map(n -> sample(n).path("type").asText()).distinct()).containsExactlyInAnyOrderElementsOf(EVENT_TYPES);
    }

    @ParameterizedTest
    @MethodSource("samples")
    void everySamplePassesTheEnvelopeTheCoreAndItsPayloadSchema(String name) {
        ObjectNode s = sample(name);
        String type = s.path("type").asText();
        assertThat(C.validateEnvelope(s)).isEmpty();
        assertThat(C.validateCore(s)).isEmpty();
        assertThat(C.validatePayload(type, s.path("payload"))).isEmpty();
        assertThat(s.path("aggregate").path("kind").asText()).isEqualTo(KINDS.get(type));
        assertThat(C.resolvedDocuments()).isPositive();
    }

    @ParameterizedTest
    @MethodSource("samples")
    void removingAnyRequiredFieldFails(String name) {
        ObjectNode s = sample(name);
        for (String field : List.of("eventId", "seq", "type", "version", "occurredAt", "aggregate", "payload")) {
            ObjectNode missing = s.deepCopy();
            missing.remove(field);
            assertThat(C.validateEnvelope(missing)).as("without envelope.%s", field).isNotEmpty();
        }
        List<List<String>> paths = new ArrayList<>();
        requiredPaths(payloadSchema(s.path("type").asText()), List.of(), paths);
        assertThat(paths).isNotEmpty();
        for (List<String> path : paths) {
            ObjectNode missing = s.deepCopy();
            ObjectNode parent = (ObjectNode) missing.path("payload");
            for (String step : path.subList(0, path.size() - 1)) {
                parent = (ObjectNode) parent.path(step);
            }
            if (!parent.has(path.get(path.size() - 1))) {
                continue;
            }
            parent.remove(path.get(path.size() - 1));
            assertThat(C.validateEnvelope(missing)).as("without payload.%s", String.join(".", path)).isNotEmpty();
        }
    }

    private static void requiredPaths(JsonNode schema, List<String> prefix, List<List<String>> out) {
        for (JsonNode r : schema.path("required")) {
            List<String> path = new ArrayList<>(prefix);
            path.add(r.asText());
            out.add(path);
        }
        schema.path("properties").fields().forEachRemaining(e -> {
            if (e.getValue().has("required")) {
                List<String> path = new ArrayList<>(prefix);
                path.add(e.getKey());
                requiredPaths(e.getValue(), path, out);
            }
        });
    }

    // ------------------------------------------------------------------ 포털 Envelope ⊇ 핵심

    static Stream<Arguments> coreViolations() {
        return Stream.of(
                Arguments.argumentSet("eventId not a uuid", (Consumer<ObjectNode>) s -> s.put("eventId", "not-a-uuid")),
                Arguments.argumentSet("eventId upper case", (Consumer<ObjectNode>) s -> s.put("eventId", s.path("eventId").asText().toUpperCase(Locale.ROOT))),
                Arguments.argumentSet("seq 0", (Consumer<ObjectNode>) s -> s.put("seq", 0)),
                Arguments.argumentSet("seq string", (Consumer<ObjectNode>) s -> s.put("seq", "1")),
                Arguments.argumentSet("version 0", (Consumer<ObjectNode>) s -> s.put("version", 0)),
                Arguments.argumentSet("type lower case", (Consumer<ObjectNode>) s -> s.put("type", "commissionCalculated")),
                Arguments.argumentSet("occurredAt without offset", (Consumer<ObjectNode>) s -> s.put("occurredAt", "2026-08-20T10:15:30")),
                Arguments.argumentSet("occurredAt date only", (Consumer<ObjectNode>) s -> s.put("occurredAt", "2026-08-20")),
                Arguments.argumentSet("aggregate.kind lower case", (Consumer<ObjectNode>) s -> ((ObjectNode) s.path("aggregate")).put("kind", "comm_calc")),
                Arguments.argumentSet("aggregate.id empty", (Consumer<ObjectNode>) s -> ((ObjectNode) s.path("aggregate")).put("id", "")),
                Arguments.argumentSet("aggregate without kind", (Consumer<ObjectNode>) s -> ((ObjectNode) s.path("aggregate")).remove("kind")),
                Arguments.argumentSet("payload not an object", (Consumer<ObjectNode>) s -> s.put("payload", "x")));
    }

    @ParameterizedTest
    @MethodSource("coreViolations")
    void everythingTheCoreRejectsThePortalEnvelopeRejects(Consumer<ObjectNode> mutation) {
        ObjectNode doc = sample("02-CommissionCalculated.json");
        mutation.accept(doc);
        assertThat(C.validateCore(doc)).as("core").isNotEmpty();
        assertThat(C.validateEnvelope(doc)).as("portal envelope").isNotEmpty();
    }

    // ------------------------------------------------------------------ 짝·열거·열린 필드

    @Test
    void aPayloadOfAnotherTypeIsRejected() {
        Map<String, ObjectNode> byType = new TreeMap<>();
        PortalContract.sampleNames().forEach(n -> byType.putIfAbsent(sample(n).path("type").asText(), sample(n)));
        for (int i = 0; i < EVENT_TYPES.size(); i++) {
            ObjectNode doc = byType.get(EVENT_TYPES.get(i)).deepCopy();
            doc.set("payload", byType.get(EVENT_TYPES.get((i + 1) % EVENT_TYPES.size())).path("payload").deepCopy());
            assertThat(C.validateEnvelope(doc)).as("%s carrying a foreign payload", EVENT_TYPES.get(i)).isNotEmpty();
        }
    }

    @ParameterizedTest
    @MethodSource("samples")
    void aggregateKindMustBeTheOnePairedWithTheType(String name) {
        ObjectNode s = sample(name);
        Set<String> others = new LinkedHashSet<>(KINDS.values());
        others.remove(s.path("aggregate").path("kind").asText());
        for (String kind : others) {
            ObjectNode doc = s.deepCopy();
            ((ObjectNode) doc.path("aggregate")).put("kind", kind);
            assertThat(C.validateEnvelope(doc)).as("%s with kind %s", s.path("type").asText(), kind).isNotEmpty();
        }
    }

    @Test
    void unknownTypeOrVersionIsRejectedEvenWhenTheCoreAcceptsIt() {
        ObjectNode unknown = sample("09-PayoutCompleted.json");
        unknown.put("type", "BonusGranted");
        assertThat(C.validateCore(unknown)).isEmpty();
        assertThat(C.validateEnvelope(unknown)).isNotEmpty();
        ObjectNode v2 = sample("09-PayoutCompleted.json");
        v2.put("version", 2);
        assertThat(C.validateCore(v2)).isEmpty();
        assertThat(C.validateEnvelope(v2)).isNotEmpty();
    }

    @Test
    void statementAndPayoutAggregateIdsAreAgentColonYm() {
        for (String file : List.of("07-StatementEstimated.json", "08-StatementConfirmed.json", "09-PayoutCompleted.json")) {
            ObjectNode doc = sample(file);
            ((ObjectNode) doc.path("aggregate")).put("id", "A0001");
            assertThat(C.validateEnvelope(doc)).as(file).isNotEmpty();
            ((ObjectNode) doc.path("aggregate")).put("id", "A0001:202613");
            assertThat(C.validateEnvelope(doc)).as(file).isNotEmpty();
        }
    }

    @Test
    void unknownFieldsAreAllowedInTheEnvelopeAndThePayload() {
        ObjectNode doc = sample("02-CommissionCalculated.json");
        doc.put("traceId", "abc");
        ((ObjectNode) doc.path("payload")).put("futureField", 1);
        ((ObjectNode) doc.path("payload").path("basis")).put("tier", "B");
        assertThat(C.validateEnvelope(doc)).isEmpty();
    }

    @Test
    void ratesAndMultiplesAreDecimalStringsNotNumbers() {
        ObjectNode calc = sample("02-CommissionCalculated.json");
        ((ObjectNode) calc.path("payload").path("basis")).put("rate", 9.333333);
        assertThat(C.validateEnvelope(calc)).as("basis.rate as a JSON number").isNotEmpty();
        ObjectNode calc2 = sample("02-CommissionCalculated.json");
        ((ObjectNode) calc2.path("payload").path("basis")).put("payoutRate", "0.1234567");
        assertThat(C.validateEnvelope(calc2)).as("more than 6 decimals").isNotEmpty();
        ObjectNode opened = sample("01-LimitLedgerOpened.json");
        ((ObjectNode) opened.path("payload")).put("limitMultiple", 12);
        assertThat(C.validateEnvelope(opened)).as("limitMultiple as a JSON number").isNotEmpty();
        ((ObjectNode) opened.path("payload")).put("limitMultiple", "012");
        assertThat(C.validateEnvelope(opened)).as("leading zero").isNotEmpty();
        ObjectNode calc3 = sample("02-CommissionCalculated.json");
        ((ObjectNode) calc3.path("payload")).put("amount", 2_800_000.5);
        assertThat(C.validateEnvelope(calc3)).as("non-integer amount").isNotEmpty();
    }

    // ------------------------------------------------------------------ v1.1·v1.1.1 — 엔진에 없는 데이터는 선택

    static Stream<Arguments> optional() {
        return Stream.of(
                Arguments.argumentSet("K1", "02-CommissionCalculated.json", List.of("policySummary"), List.of("productName", "insuredMasked")),
                Arguments.argumentSet("K2", "02-CommissionCalculated.json", List.of("basis"), List.of("rate", "payoutRate", "installmentNo")),
                Arguments.argumentSet("K3", "16-AgentSnapshot.json", List.<String>of(), List.of("grade", "role")),
                Arguments.argumentSet("K4", "08-StatementConfirmed.json", List.<String>of(), List.of("clawbackOffset", "withholdingTax", "net")),
                Arguments.argumentSet("L1", "17-LimitLedgerRebased.json", List.<String>of(), List.of("postingSeq")));
    }

    @ParameterizedTest
    @MethodSource("optional")
    void fieldsTheEngineDoesNotHaveAreOptional(String file, List<String> objectPath, List<String> fields) {
        ObjectNode doc = sample(file);
        ObjectNode target = (ObjectNode) doc.path("payload");
        for (String step : objectPath) {
            target = (ObjectNode) target.path(step);
        }
        for (String f : fields) {
            assertThat(target.has(f)).isTrue();
            target.remove(f);
        }
        assertThat(C.validateEnvelope(doc)).isEmpty();
    }

    @Test
    void payoutCompletedSettledAmountsAreOptionalIntegersAndRebasedPostingSeqStaysPositive() {
        ObjectNode doc = sample("09-PayoutCompleted.json");
        ((ObjectNode) doc.path("payload")).put("runSeq", 1).put("gross", 3_600_000).put("clawbackOffset", 0).put("receivableOffset", 0)
                .put("carriedReceivable", 0).put("payable", 3_600_000).put("withholdingTax", 118_800).put("net", 3_481_200);
        assertThat(C.validateEnvelope(doc)).isEmpty();
        ObjectNode bad = doc.deepCopy();
        ((ObjectNode) bad.path("payload")).put("net", "1");
        assertThat(C.validateEnvelope(bad)).isNotEmpty();
        ObjectNode run0 = doc.deepCopy();
        ((ObjectNode) run0.path("payload")).put("runSeq", 0);
        assertThat(C.validateEnvelope(run0)).isNotEmpty();
        ObjectNode rebased = sample("17-LimitLedgerRebased.json");
        ((ObjectNode) rebased.path("payload")).put("postingSeq", 0);
        assertThat(C.validateEnvelope(rebased)).isNotEmpty();
    }

    // ------------------------------------------------------------------ 피드 응답 (OpenAPI 1.1.0)

    @Test
    void feedResponseNeedsTenantIdAndIntegerCursors() {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        ArrayNode events = response.putArray("events");
        PortalContract.sampleNames().stream().limit(9).forEach(n -> events.add(sample(n)));
        response.put("tenantId", "GA01").put("nextSeq", 9).put("headSeq", 13).put("schemaVersion", 1);
        assertThat(C.validateApiSchema("FeedResponse", response)).isEmpty();
        ObjectNode noTenant = response.deepCopy();
        noTenant.remove("tenantId");
        assertThat(C.validateApiSchema("FeedResponse", noTenant)).isNotEmpty();
        ObjectNode badTenant = response.deepCopy();
        badTenant.put("tenantId", "ga01");
        assertThat(C.validateApiSchema("FeedResponse", badTenant)).isNotEmpty();
        ObjectNode stringCursor = response.deepCopy();
        stringCursor.put("nextSeq", "9");
        assertThat(C.validateApiSchema("FeedResponse", stringCursor)).isNotEmpty();
        ObjectNode badEvent = response.deepCopy();
        ((ObjectNode) badEvent.path("events").get(0)).remove("payload");
        assertThat(C.validateApiSchema("FeedResponse", badEvent)).as("an invalid event inside the feed").isNotEmpty();

        ObjectNode problem = JsonNodeFactory.instance.objectNode().put("code", "AFTER_BEYOND_HEAD").put("message", "afterSeq 20 > headSeq 13");
        assertThat(C.validateApiSchema("Problem", problem)).isEmpty();
        assertThat(C.validateApiSchema("Problem", JsonNodeFactory.instance.objectNode().put("message", "x"))).isNotEmpty();
    }

    /** 사본의 IRI 밖(원격)은 조회하지 않고 예외다 — 포털이 아닌 호스트를 가리키는 문서는 풀리지 않는다. */
    @Test
    void anIriOutsideTheRepositoryCopiesIsNeverFetched() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> C.schema("https://example.org/contracts/x.json").validate(JsonNodeFactory.instance.objectNode()))
                .hasStackTraceContaining("원격 스키마 조회 금지");
    }
}
