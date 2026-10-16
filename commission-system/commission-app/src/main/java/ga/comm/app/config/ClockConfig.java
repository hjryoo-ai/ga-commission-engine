package ga.comm.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 앱 전역 시계 하나(Phase E1 Q5) — 공시 등급(스냅샷 ID 날짜·미래일 판정), 마감 확정 시각({@code closed_at}), 지급 확정 시각({@code paid_at}),
 * 아웃박스 {@code occurredAt}이 같은 빈을 쓴다. 시간대는 Asia/Seoul 고정(E3에서 공시 등급용으로 정한 그대로 — 날짜 규약 무변경).
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
