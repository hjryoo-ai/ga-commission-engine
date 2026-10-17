package ga.comm.outbox.contract;

import com.fasterxml.jackson.databind.JsonNode;
import ga.comm.clawback.ReceivableStatus;
import ga.comm.deferral.DeferralTransitionReason;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2 ⓪b — 포털 계약 v1.2가 닫은 두 어휘가 엔진 열거와 글자·순서까지 같다(어휘의 정본은 엔진 열거 — 어긋나면 틀린 쪽이 고친다, 포털 Phase 2
 * 지시문 §0). 채권 금액은 0 이상(엔진 {@code ClawbackReceivable.rehydrate}가 이미 강제)이고 계약도 {@code nonNegativeWon}이다.
 */
class PortalVocabularyTest {

    private static JsonNode property(String type, String field) {
        return PortalContract.portalFile("events/v1/payloads/" + type + ".v1.schema.json").path("properties").path(field);
    }

    private static List<String> enumOf(String type, String field) {
        List<String> out = new java.util.ArrayList<>();
        property(type, field).path("enum").forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void 분급_전이_사유는_엔진_열거와_같다() {
        assertThat(enumOf("DeferralStatusChanged", "reason"))
                .containsExactlyElementsOf(Arrays.stream(DeferralTransitionReason.values()).map(Enum::name).toList());
    }

    @Test
    void 채권_상태는_엔진_열거와_같다() {
        assertThat(enumOf("ClawbackReceivableUpdated", "status"))
                .containsExactlyElementsOf(Arrays.stream(ReceivableStatus.values()).map(Enum::name).toList());
    }

    @Test
    void 채권_금액은_0_이상이다() {
        for (String f : List.of("amount", "remaining")) {
            assertThat(property("ClawbackReceivableUpdated", f).path("$ref").asText()).as(f).endsWith("#/$defs/nonNegativeWon");
        }
    }
}
