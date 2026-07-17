package ga.comm.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 웹 계층 JSON 규약 (설계서 §10 Phase 12, 부록 B-1의 연장).
 *
 * <p>금액 필드는 DTO에서 {@code long}(원 단위) — 웹 계층에서도 double 경유가 없다.
 * 날짜는 ISO(yyyy-MM-dd)로 고정하고(타임스탬프 직렬화 비활성), 마감월은 DTO에서 이미
 * yyyyMM 문자열이다. 이 ObjectMapper는 운영 빈과 슬라이스 테스트가 공유해 포맷을 한곳에 고정한다.
 */
@Configuration
public class WebConfig {

    public static ObjectMapper apiObjectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return apiObjectMapper();
    }
}
