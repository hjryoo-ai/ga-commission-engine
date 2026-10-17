package ga.comm.infra.it;

import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.recipient.AgentDirectory;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.type.Direction;
import ga.comm.domain.type.EventType;
import ga.comm.infra.OraclePersistence;
import ga.comm.rule.RuleRepository;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.rule.model.ChannelType;
import ga.comm.rule.model.LimitRule;
import ga.comm.rule.model.OrgLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2 ⑤·C8 — 로컬 스택 시드 {@code localstack/seed/a1.sql}을 실제 Oracle에 넣으면 Oracle 룰 저장소·설계사 디렉터리의 답이 시험이 쓰는 인메모리
 * 픽스처({@code RuleFixtures.standardRules()}·{@code CalcTestHarness.standardDirectory()})의 답과 같다 — 조회 격자 전체에서. 로컬 스택 피드가
 * 두 번째 판과 바이트로 같다는 CI 잡의 증명(C8)을 시드 쪽에서 미리 좁힌다(주입 S11 — 요율 하나만 달라도 여기서 실패).
 */
class LocalStackSeedIT {

    private static final List<LocalDate> DATES = List.of(LocalDate.of(2024, 12, 31), LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 12, 31),
            LocalDate.of(2027, 1, 1), LocalDate.of(2027, 2, 15), LocalDate.of(2028, 12, 31), LocalDate.of(2029, 1, 1), LocalDate.of(2031, 5, 5));
    private static final List<CommTypeCode> TYPES = List.of(CommTypeCode.FY_COMM, CommTypeCode.RENEWAL, CommTypeCode.INCENTIVE,
            CommTypeCode.SETTLEMENT_SUPPORT, CommTypeCode.OVERRIDE, CommTypeCode.DEFERRED, CommTypeCode.CLAWBACK, new CommTypeCode("NONE"));

    private final OraclePersistence p = OracleTestSupport.persistence();

    @BeforeEach
    void seed() throws IOException {
        OracleTestSupport.cleanAll();
        String path = System.getProperty("engine.localstackSeed");
        assertThat(path).as("engine.localstackSeed(Gradle이 준다)").isNotNull();
        JdbcTemplate jdbc = new JdbcTemplate(OracleTestSupport.dataSource());
        for (String sql : statements(Files.readString(Path.of(path), StandardCharsets.UTF_8))) {
            jdbc.update(sql);
        }
    }

    /** 주석 줄을 빼고 줄 끝 세미콜론으로 나눈다(LocalStackScenario와 같은 규칙). */
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
        assertThat(current.toString().strip()).as("세미콜론으로 끝나지 않은 문장").isEmpty();
        return out;
    }

    private static List<String> grid(RuleRepository oracle, RuleRepository memory, Function<RuleRepository, Object> query, List<String> out,
                                          String label) {
        out.add(label + " oracle=" + norm(query.apply(oracle)) + " memory=" + norm(query.apply(memory)));
        return out;
    }

    /** {@code LimitRule.limitMultiple}의 BigDecimal 자릿수(12.00 vs 12)만 정규화 — 나머지는 레코드 동등(Rate는 값 비교). */
    private static Object norm(Object o) {
        if (o instanceof Optional<?> opt && opt.isPresent() && opt.get() instanceof LimitRule r) {
            return Optional.of(new LimitRule(r.ruleId(), r.channelType(), r.limitMultiple().stripTrailingZeros(), r.fyWindowMonths(),
                    r.overLimitAction(), r.clawbackRestores(), r.period()));
        }
        return o;
    }

    @Test
    void 시드의_룰_조회는_인메모리_표준_룰과_같다() {
        RuleRepository oracle = p.ruleRepository();
        RuleRepository memory = RuleFixtures.standardRules();
        InsurerCode insurer = RuleFixtures.INSURER;
        ProductKey product = RuleFixtures.PRODUCT;
        List<String> mismatches = new ArrayList<>();
        int checked = 0;
        for (LocalDate d : DATES) {
            List<String> lines = new ArrayList<>();
            for (CommTypeCode type : TYPES) {
                List<Integer> installments = new ArrayList<>(Arrays.asList((Integer) null));
                for (int i = 1; i <= 30; i++) {
                    installments.add(i);
                }
                for (Integer inst : installments) {
                    grid(oracle, memory, r -> r.findRate(Direction.OUTBOUND, insurer, product, type, inst, d), lines, "rate " + type + "/" + inst + "@" + d);
                }
                grid(oracle, memory, r -> r.insurersWithRate(Direction.OUTBOUND, product, type, d), lines, "insurers " + type + "@" + d);
                grid(oracle, memory, r -> r.findCommTypeAttr(type, d), lines, "attr " + type + "@" + d);
                for (String grade : List.of("SR", "JR", "XX")) {
                    grid(oracle, memory, r -> r.findPayoutRate(grade, type, d), lines, "payout " + grade + "/" + type + "@" + d);
                }
                for (OrgLevel level : OrgLevel.values()) {
                    grid(oracle, memory, r -> r.findOrgOverrideRate(level, type, d), lines, "override " + level + "/" + type + "@" + d);
                }
            }
            for (ChannelType channel : ChannelType.values()) {
                grid(oracle, memory, r -> r.findLimitRule(channel, d), lines, "limit " + channel + "@" + d);
            }
            grid(oracle, memory, r -> r.findDeferralCurve(d), lines, "curve@" + d);
            for (EventType et : EventType.values()) {
                grid(oracle, memory, r -> r.findClawbackTable(product, et, d), lines, "clawback " + et + "@" + d);
            }
            for (String line : lines) {
                checked++;
                String[] parts = line.split(" oracle=| memory=");
                if (!parts[1].equals(parts[2])) {
                    mismatches.add(line);
                }
            }
        }
        assertThat(mismatches).as("시드 SQL ≠ RuleFixtures.standardRules()").isEmpty();
        assertThat(checked).isGreaterThan(3_000);
    }

    @Test
    void 시드의_설계사_디렉터리는_인메모리_표준_디렉터리와_같다() {
        AgentDirectory oracle = p.agentDirectory();
        AgentDirectory memory = CalcTestHarness.standardDirectory();
        for (String agent : List.of("A-1001", "A-9999")) {
            for (LocalDate d : DATES) {
                assertThat(oracle.gradeOf(new AgentId(agent), d)).as("grade " + agent + "@" + d).isEqualTo(memory.gradeOf(new AgentId(agent), d));
                assertThat(oracle.orgChainOf(new AgentId(agent), d)).as("orgChain " + agent + "@" + d)
                        .isEqualTo(memory.orgChainOf(new AgentId(agent), d));
            }
        }
        assertThat(oracle.orgChainOf(new AgentId("A-1001"), LocalDate.of(2026, 8, 1))).hasSize(3);
    }
}
