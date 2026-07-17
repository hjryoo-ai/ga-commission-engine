package ga.comm.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 평면 문자열 맵 ↔ JSON CLOB 직렬화 (POLICY_EVENT.payload, INBOUND_STATEMENT.raw_fields).
 * JSON CLOB 안에서는 빈 문자열 값도 보존된다 — Oracle의 ''=NULL 접힘은 VARCHAR2 컬럼에만 해당.
 */
public final class JsonMaps {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };

    private JsonMaps() {
    }

    public static String write(Map<String, String> map) {
        try {
            return MAPPER.writeValueAsString(map == null ? Map.of() : map);
        } catch (Exception e) {
            throw new IllegalStateException("맵 JSON 직렬화 실패", e);
        }
    }

    public static Map<String, String> read(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("맵 JSON 역직렬화 실패: " + json, e);
        }
    }
}
