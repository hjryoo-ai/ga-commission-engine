package ga.comm.outbox.fixture;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 이벤트 쪽 마감 불변식(E1 C8, P11) — 피드 순서로 읽어 {@code StatementConfirmed(agentId, closeYm)} 뒤에 같은 설계사·같은 마감월로 귀속된
 * {@code CommissionCalculated}·{@code AdjustmentPosted}가 나오면 위반이다(마감된 달의 숫자는 바뀌지 않는다, 설계서 §6.4). 운영에서 이것을 지키는
 * 것은 {@code CloseYmResolver}(마감월이면 다음 열린 달)이고 {@code ClosedMonthGuardedCalcStore}는 운영 조립에 없다.
 */
public final class CloseInvariant {

    private CloseInvariant() {
    }

    public static List<String> violations(List<JsonNode> envelopesInSeqOrder) {
        Set<String> confirmed = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (JsonNode e : envelopesInSeqOrder) {
            JsonNode p = e.path("payload");
            String key = p.path("agentId").asText() + ":" + p.path("closeYm").asText();
            switch (e.path("type").asText()) {
                case "StatementConfirmed" -> confirmed.add(key);
                case "CommissionCalculated", "AdjustmentPosted" -> {
                    if (confirmed.contains(key)) {
                        out.add("seq " + e.path("seq").asLong() + " " + e.path("type").asText() + " → 이미 확정된 " + key);
                    }
                }
                default -> {
                }
            }
        }
        return out;
    }
}
