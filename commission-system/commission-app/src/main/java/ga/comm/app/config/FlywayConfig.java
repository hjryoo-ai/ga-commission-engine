package ga.comm.app.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Flyway 기동 전략 (Phase 16). <b>운영(prod)에서는 기동 시 {@code validate}만</b> 수행한다 — 스키마
 * 변경(migrate)은 통제된 별도 절차(운영 DBA/배포 파이프라인의 {@code flywayMigrate})로만 한다. 앱이
 * 뜨면서 임의로 스키마를 바꾸지 않는다.
 *
 * <p>기본/테스트 프로파일에는 이 전략 빈이 없으므로 Flyway 자동설정이 {@code migrate}한다(개발·스모크
 * 편의 — 프레시 컨테이너에 스키마를 세운다).
 */
@Configuration(proxyBeanMethods = false)
public class FlywayConfig {

    @Bean
    @Profile("prod")
    public FlywayMigrationStrategy validateOnly() {
        return Flyway::validate;
    }
}
