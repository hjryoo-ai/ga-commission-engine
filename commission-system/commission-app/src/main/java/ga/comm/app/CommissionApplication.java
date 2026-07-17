package ga.comm.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration;

import java.util.TimeZone;

/**
 * GA 수수료 시스템 실행 진입점 (설계서 §10 Phase 16 — 운영 조립).
 *
 * <p><b>배치 자동실행 금지</b>: {@link BatchAutoConfiguration}을 제외해 Spring Batch의
 * {@code JobLauncherApplicationRunner}가 기동 시 잡을 자동 실행하지 않게 한다. 잡은 오직 명시적
 * 파라미터로만 기동한다({@code BatchLaunchController}, OPERATOR 권한). 배치 저장소·런처는
 * {@code BatchConfig}에서 수동 배선한다({@code BatchRuntime}).
 *
 * <p><b>결정론 — 타임존</b>: {@code close_ym}·기준일 산정이 시스템 클럭에 의존하므로 배포 환경의
 * 기본 타임존 차이로 귀속월이 흔들리면 안 된다. {@code main}에서 JVM 기본 타임존을 Asia/Seoul로 고정한다
 * (배포 시 {@code -Duser.timezone=Asia/Seoul -Dfile.encoding=UTF-8}도 함께 명시 — README 운영 절).
 */
// scanBasePackages: 컨트롤러·advice는 ga.comm.api.web(앱 패키지 밖)에 있고, 배선 @Configuration은
// ga.comm.app에 있다. ga.comm 전체를 스캔한다 — 이 코드베이스의 스테레오타입은 웹 5개 + 앱 config뿐이라
// (스토어·서비스·배치는 전부 명시 @Bean) 예기치 않은 컴포넌트가 딸려오지 않는다.
@SpringBootApplication(scanBasePackages = "ga.comm", exclude = BatchAutoConfiguration.class)
public class CommissionApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(CommissionApplication.class, args);
    }
}
