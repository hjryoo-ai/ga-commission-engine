package ga.comm.localstack;

import ga.comm.app.CommissionApplication;
import ga.comm.batch.BatchRequestRunner;
import ga.comm.calc.CommissionCalculator;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.time.CloseYm;
import ga.comm.outbox.OutboxStore;
import ga.comm.outbox.fixture.OutboxEngine;
import ga.comm.outbox.fixture.SteppingClock;
import ga.comm.outbox.publish.OutboxPublisher;
import ga.comm.outbox.scenario.A1Scenario;
import ga.comm.outbox.scenario.A1ScenarioRunner;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 로컬 스택 시나리오 실행기(E2 ⑤, 계획 P8·Q9) — <b>운영 jar 밖</b>. 앱 조립({@link CommissionApplication})을 그대로 띄우되 빈 둘만 바꿔 끼운다:
 * {@code clock} = 단계 시계, {@code outboxPublisher} = 순차 eventId(00000000-0000-0000-0000-{n}). 그다음 빈 DB(Flyway 직후)에 시드 SQL을 넣고
 * A-1 두 번째 판(계산 → 202608 마감 잡 → 지급 런 잡 → 해약 → 202702 잠정 명세 잡)을 <b>운영 서비스·운영 잡</b>으로 돌린 뒤 끝난다.
 * 계산만 서비스 직접 호출이다 — 엔진에 정책 이벤트를 받는 운영 경로가 없다(E1 P12). 피드는 그 뒤에 보통 앱이 낸다(부여는 lazy, {@code event_no} 순).
 *
 * <p>인자: 시드 SQL 경로. DB 접속·테넌트·서비스 토큰 해시는 앱과 같은 환경변수({@code DB_URL}·{@code DB_USERNAME}·{@code DB_PASSWORD}·
 * {@code ENGINE_TENANT_ID}·{@code ENGINE_SERVICE_TOKEN_SHA256}) — {@code localstack/up.sh}가 준다. 값을 출력하지 않는다.
 */
public final class LocalStackScenario {

    private LocalStackScenario() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: LocalStackScenario <seed.sql>");
        }
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        A1Scenario s = A1Scenario.portal150000();
        SteppingClock clock = new SteppingClock(s.times().calc().toInstant(), ZoneId.of("Asia/Seoul"));
        Supplier<UUID> ids = OutboxEngine.sequentialIds();

        SpringApplication app = new SpringApplication(CommissionApplication.class);
        // 웹 계층까지 앱 조립 그대로(보안 체인이 서블릿 HttpSecurity를 요구한다) — 포트는 임의, 시나리오 중 요청은 받지 않는다
        app.setDefaultProperties(Map.of("server.port", "0"));
        app.addInitializers(ctx -> ctx.addBeanFactoryPostProcessor(beanFactory -> override(beanFactory, clock, ids)));
        try (ConfigurableApplicationContext ctx = app.run()) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM OUTBOX_EVENT", Long.class);
            if (rows == null || rows != 0) {
                throw new IllegalStateException("빈 DB에서만 돈다(OUTBOX_EVENT " + rows + "행) — localstack/up.sh는 컨테이너를 새로 만든다");
            }
            TransactionTemplate tx = ctx.getBean("transactionTemplate", TransactionTemplate.class);
            List<String> seed = statements(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8));
            tx.executeWithoutResult(status -> seed.forEach(jdbc::update));
            System.out.println("[localstack] 시드 " + seed.size() + "문장");

            A1ScenarioRunner.secondEdition(s, services(ctx, tx), clock);
            System.out.println("[localstack] A-1 두 번째 판 완료 — OUTBOX_EVENT "
                    + jdbc.queryForObject("SELECT COUNT(*) FROM OUTBOX_EVENT", Long.class) + "행");
        }
    }

    /** 앱의 {@code clock}·{@code outboxPublisher} 정의를 바꿔 끼운다 — 설정 클래스가 정의를 다 등록한 뒤(일반 BeanFactoryPostProcessor 단계). */
    private static void override(ConfigurableListableBeanFactory beanFactory, Clock clock, Supplier<UUID> ids) {
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
        for (String name : List.of("clock", "outboxPublisher")) {
            if (!registry.containsBeanDefinition(name)) {
                throw new IllegalStateException("앱 조립에 빈 '" + name + "'이 없다 — 바꿔 끼울 수 없다");
            }
            registry.removeBeanDefinition(name);
        }
        registry.registerBeanDefinition("clock", BeanDefinitionBuilder.genericBeanDefinition(Clock.class, () -> clock).getBeanDefinition());
        registry.registerBeanDefinition("outboxPublisher", BeanDefinitionBuilder.genericBeanDefinition(OutboxPublisher.class,
                () -> new OutboxPublisher(beanFactory.getBean(OutboxStore.class), clock, ids)).getBeanDefinition());
    }

    private static A1ScenarioRunner.Services services(ConfigurableApplicationContext ctx, TransactionTemplate tx) {
        CommissionCalculator calculator = ctx.getBean(CommissionCalculator.class);
        BatchRequestRunner runner = ctx.getBean(BatchRequestRunner.class);
        return new A1ScenarioRunner.Services() {
            @Override
            public void process(PolicyEvent event) {
                tx.executeWithoutResult(status -> calculator.process(event));
            }

            @Override
            public void close(CloseYm ym, String actor) {
                run(runner, ctx.getBean("monthCloseJob", Job.class), new JobParametersBuilder()
                        .addString("request_id", "localstack-a1-close-" + ym).addString("close_ym", ym.toString())
                        .addString("closed_by", actor).toJobParameters());
            }

            @Override
            public void payout(CloseYm ym, int runSeq) {
                run(runner, ctx.getBean("payoutRunJob", Job.class), new JobParametersBuilder()
                        .addString("request_id", "localstack-a1-payout-" + ym + "-" + runSeq).addString("close_ym", ym.toString())
                        .addLong("run_seq", (long) runSeq).toJobParameters());
            }

            @Override
            public void estimate(CloseYm ym) {
                run(runner, ctx.getBean("statementEstimateJob", Job.class), new JobParametersBuilder()
                        .addString("request_id", "localstack-a1-estimate-" + ym).addString("close_ym", ym.toString()).toJobParameters());
            }
        };
    }

    private static void run(BatchRequestRunner runner, Job job, JobParameters params) {
        BatchRequestRunner.Outcome outcome = runner.submit(job, params);
        if (outcome.disposition() != BatchRequestRunner.Disposition.COMPLETED) {
            throw new IllegalStateException(job.getName() + " 실패: " + outcome.disposition() + " " + outcome.execution().getExitStatus());
        }
        System.out.println("[localstack] " + job.getName() + " " + outcome.execution().getExitStatus().getExitCode());
    }

    /** 주석 줄을 빼고 줄 끝 세미콜론으로 나눈다(LocalStackSeedIT와 같은 규칙). */
    static List<String> statements(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\n")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("--")) {
                continue;
            }
            current.append(t).append(' ');
            if (t.endsWith(";")) {
                String sql = current.toString().strip();
                out.add(sql.substring(0, sql.length() - 1));
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            throw new IllegalArgumentException("세미콜론으로 끝나지 않은 문장: " + current);
        }
        return out;
    }
}
