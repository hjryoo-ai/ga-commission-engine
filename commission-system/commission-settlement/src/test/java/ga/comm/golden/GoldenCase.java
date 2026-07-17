package ga.comm.golden;

import ga.comm.calc.fixture.InMemoryAgentDirectory;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Rate;
import ga.comm.domain.money.RoundingPolicy;
import ga.comm.domain.type.Direction;
import ga.comm.domain.type.EventType;
import ga.comm.rule.fixture.InMemoryRuleStore;
import ga.comm.rule.model.ChannelType;
import ga.comm.rule.model.ClawbackTable;
import ga.comm.rule.model.CommRateRule;
import ga.comm.rule.model.CommTypeAttr;
import ga.comm.rule.model.DeferralCurve;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.LimitRule;
import ga.comm.rule.model.OrgLevel;
import ga.comm.rule.model.OrgOverrideRate;
import ga.comm.rule.model.OverLimitAction;
import ga.comm.rule.model.PayoutRateRule;
import ga.comm.rule.model.RateStatus;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 하나의 골든 케이스 = 자기완결적 CSV 번들(설계서 §8.1, Phase 14).
 *
 * <p>디렉터리 하나가 케이스 하나다. 그 안의 CSV들이 [룰 시드 + 타임라인(이벤트/오퍼레이션) + 기대 결과]를
 * 전부 담는다 — 공유 시드에 기대지 않는다. 규정이 바뀌면 기존 케이스를 고치지 말고 새 케이스 디렉터리를
 * 추가한다(effective-dating 철학). 작성 규약은 {@code golden/README.md} 참조.
 */
final class GoldenCase {

    final String id;
    final String title;
    final String axis;
    final Set<String> features;   // LIMIT / DEFERRAL / CLAWBACK
    final InsurerCode insurer;
    final ProductKey product;
    final AgentId agent;
    final long defaultPremium;

    final InMemoryRuleStore rules;
    final InMemoryAgentDirectory directory;
    final List<Step> timeline;
    final List<ExpectedLine> expectedLines;
    final List<ExpectedLedger> expectedLedgers;
    final List<ExpectedSchedule> expectedSchedule;

    private GoldenCase(String id, String title, String axis, Set<String> features,
                       InsurerCode insurer, ProductKey product, AgentId agent, long defaultPremium,
                       InMemoryRuleStore rules, InMemoryAgentDirectory directory,
                       List<Step> timeline, List<ExpectedLine> expectedLines,
                       List<ExpectedLedger> expectedLedgers, List<ExpectedSchedule> expectedSchedule) {
        this.id = id;
        this.title = title;
        this.axis = axis;
        this.features = features;
        this.insurer = insurer;
        this.product = product;
        this.agent = agent;
        this.defaultPremium = defaultPremium;
        this.rules = rules;
        this.directory = directory;
        this.timeline = timeline;
        this.expectedLines = expectedLines;
        this.expectedLedgers = expectedLedgers;
        this.expectedSchedule = expectedSchedule;
    }

    enum StepType { EVENT, APPROVE_RATE, REBOOK, RELEASE, DEDUCT_CLAWBACK }

    /** 타임라인 한 단계 — 실행 순서는 파일 순서다. cells는 step 유형별로 해석한다(README 범례). */
    record Step(StepType type, String ref, GoldenCsv.Row cells) {
    }

    /** 기대 커미션 라인: 특정 단계(ref) 산출물 또는 "NET"(전 상태 합산) 중 하나에 대해 비교. */
    record ExpectedLine(String eventRef, String recipientType, String recipientId,
                        String commType, long calcAmount, Long limitCut, String closeYm) {
    }

    /** 기대 한도 원장 상태(최종 누적). null 필드는 비교에서 제외. */
    record ExpectedLedger(String policyNo, String agentId, Long limitAmount, Long accumPaid,
                          Long available, LocalDate fyStart, LocalDate fyEnd) {
    }

    /** 기대 분급 스케줄 엔트리(최종 상태, 집합 비교). */
    record ExpectedSchedule(String dueYm, long amount, String status, String payCondition) {
    }

    static GoldenCase load(Path dir) {
        Map<String, String> meta = GoldenCsv.readKeyValue(dir.resolve("case.csv"));
        String id = meta.getOrDefault("id", dir.getFileName().toString());
        InsurerCode insurer = new InsurerCode(meta.getOrDefault("insurer", "SAMLIFE"));
        ProductKey product = new ProductKey(meta.getOrDefault("product", "WHOLE-LIFE-20Y"));
        AgentId agent = new AgentId(meta.getOrDefault("agent", "A-1001"));
        long defaultPremium = Long.parseLong(meta.getOrDefault("premium", "300000").trim());
        Set<String> features = new TreeSet<>();
        for (String f : meta.getOrDefault("features", "").split(";")) {
            if (!f.trim().isEmpty()) {
                features.add(f.trim().toUpperCase());
            }
        }

        InMemoryRuleStore rules = buildRules(dir, insurer, product);
        InMemoryAgentDirectory directory = buildDirectory(dir, agent);
        List<Step> timeline = loadTimeline(dir);
        List<ExpectedLine> lines = loadExpectedLines(dir);
        List<ExpectedLedger> ledgers = loadExpectedLedgers(dir);
        List<ExpectedSchedule> schedule = loadExpectedSchedule(dir);

        return new GoldenCase(id, meta.getOrDefault("title", id), meta.getOrDefault("axis", ""),
                features, insurer, product, agent, defaultPremium, rules, directory,
                timeline, lines, ledgers, schedule);
    }

    // ---- 시드 조립 (CSV → 실제 도메인 모델, RuleFixtures와 동일한 생성자) ----

    private static InMemoryRuleStore buildRules(Path dir, InsurerCode insurer, ProductKey product) {
        InMemoryRuleStore rules = new InMemoryRuleStore();

        for (GoldenCsv.Row r : GoldenCsv.readTable(dir.resolve("seed_commtypes.csv"))) {
            rules.addCommTypeAttr(new CommTypeAttr(new CommTypeCode(r.get("commType")),
                    parseBool(r.get("limitIncluded")),
                    RoundingPolicy.valueOf(r.getOr("rounding", "KRW_FLOOR")),
                    parseBool(r.get("clawbackTarget")),
                    EffectivePeriod.from(date(r.get("applyFrom")))));
        }

        long rateId = 1000;
        for (GoldenCsv.Row r : GoldenCsv.readTable(dir.resolve("seed_rates.csv"))) {
            Integer installment = r.has("installmentNo") ? Integer.parseInt(r.get("installmentNo")) : null;
            rules.addActiveRate(new CommRateRule(++rateId, Direction.OUTBOUND, insurer, product,
                    new CommTypeCode(r.get("commType")), installment, Rate.of(r.get("rate")),
                    EffectivePeriod.from(date(r.get("applyFrom"))), 1, RateStatus.ACTIVE));
        }

        for (GoldenCsv.Row r : GoldenCsv.readTable(dir.resolve("seed_payout.csv"))) {
            rules.addPayoutRate(new PayoutRateRule(r.get("grade"), new CommTypeCode(r.get("commType")),
                    Rate.of(r.get("rate")), EffectivePeriod.from(date(r.get("applyFrom")))));
        }

        for (GoldenCsv.Row r : GoldenCsv.readTable(dir.resolve("seed_overrides.csv"))) {
            rules.addOrgOverrideRate(new OrgOverrideRate(OrgLevel.valueOf(r.get("orgLevel")),
                    new CommTypeCode(r.get("commType")), Rate.of(r.get("rate")),
                    EffectivePeriod.from(date(r.get("applyFrom")))));
        }

        long limitId = 1;
        for (GoldenCsv.Row r : GoldenCsv.readTable(dir.resolve("seed_limit.csv"))) {
            rules.addLimitRule(new LimitRule(limitId++, ChannelType.valueOf(r.get("channel")),
                    new BigDecimal(r.get("multiple")), Integer.parseInt(r.get("fyWindowMonths")),
                    OverLimitAction.valueOf(r.get("overLimitAction")),
                    parseBool(r.get("clawbackRestores")),
                    EffectivePeriod.from(date(r.get("applyFrom")))));
        }

        buildDeferralCurves(dir, rules);
        buildClawbackTables(dir, rules);
        return rules;
    }

    private static void buildDeferralCurves(Path dir, InMemoryRuleStore rules) {
        // 커브 = curveId로 묶인 여러 포인트 행. 파일 순서를 보존한다(포인트 오프셋 순).
        Map<String, List<GoldenCsv.Row>> byCurve = new LinkedHashMap<>();
        for (GoldenCsv.Row r : GoldenCsv.readTable(dir.resolve("seed_deferral.csv"))) {
            byCurve.computeIfAbsent(r.get("curveId"), k -> new ArrayList<>()).add(r);
        }
        for (var entry : byCurve.entrySet()) {
            List<GoldenCsv.Row> pts = entry.getValue();
            GoldenCsv.Row head = pts.get(0);
            EffectivePeriod period = head.has("applyTo")
                    ? EffectivePeriod.of(date(head.get("applyFrom")), date(head.get("applyTo")))
                    : EffectivePeriod.from(date(head.get("applyFrom")));
            List<DeferralCurve.CurvePoint> points = pts.stream()
                    .map(p -> new DeferralCurve.CurvePoint(Integer.parseInt(p.get("monthOffset")),
                            Rate.of(p.get("ratio"))))
                    .collect(Collectors.toList());
            rules.addDeferralCurve(new DeferralCurve(Long.parseLong(entry.getKey()),
                    head.getOr("name", "curve-" + entry.getKey()), period, points));
        }
    }

    private static void buildClawbackTables(Path dir, InMemoryRuleStore rules) {
        // 환수 테이블 = eventType으로 묶인 여러 밴드 행. productKey는 공통(null).
        Map<String, List<GoldenCsv.Row>> byEvent = new LinkedHashMap<>();
        for (GoldenCsv.Row r : GoldenCsv.readTable(dir.resolve("seed_clawback.csv"))) {
            byEvent.computeIfAbsent(r.get("eventType"), k -> new ArrayList<>()).add(r);
        }
        for (var entry : byEvent.entrySet()) {
            List<GoldenCsv.Row> bands = entry.getValue();
            List<ClawbackTable.Band> bandList = bands.stream()
                    .map(b -> new ClawbackTable.Band(Integer.parseInt(b.get("bandFrom")),
                            Integer.parseInt(b.get("bandTo")), Rate.of(b.get("rate"))))
                    .collect(Collectors.toList());
            rules.addClawbackTable(new ClawbackTable(null, EventType.valueOf(entry.getKey()),
                    EffectivePeriod.from(date(bands.get(0).get("applyFrom"))), bandList));
        }
    }

    private static InMemoryAgentDirectory buildDirectory(Path dir, AgentId caseAgent) {
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        List<GoldenCsv.Row> rows = GoldenCsv.readTable(dir.resolve("seed_agent.csv"));
        for (GoldenCsv.Row r : rows) {
            AgentId agentId = new AgentId(r.getOr("agentId", caseAgent.value()));
            directory.withGrade(agentId, r.get("grade"), date(r.get("gradeFrom")));
            List<ga.comm.calc.recipient.OrgAssignment> chain = new ArrayList<>();
            if (r.has("team")) {
                chain.add(InMemoryAgentDirectory.team(r.get("team")));
            }
            if (r.has("branch")) {
                chain.add(InMemoryAgentDirectory.branch(r.get("branch")));
            }
            if (r.has("hq")) {
                chain.add(InMemoryAgentDirectory.hq(r.get("hq")));
            }
            if (!chain.isEmpty()) {
                directory.withOrgChain(agentId, date(r.getOr("orgFrom", r.get("gradeFrom"))),
                        chain.toArray(new ga.comm.calc.recipient.OrgAssignment[0]));
            }
        }
        return directory;
    }

    // ---- 타임라인·기대 로딩 ----

    private static List<Step> loadTimeline(Path dir) {
        List<Step> steps = new ArrayList<>();
        for (GoldenCsv.Row r : GoldenCsv.readTable(dir.resolve("timeline.csv"))) {
            StepType type = StepType.valueOf(r.get("step"));
            steps.add(new Step(type, r.get("ref"), r));
        }
        return steps;
    }

    private static List<ExpectedLine> loadExpectedLines(Path dir) {
        List<ExpectedLine> out = new ArrayList<>();
        for (GoldenCsv.Row r : GoldenCsv.readTable(dir.resolve("expected_lines.csv"))) {
            out.add(new ExpectedLine(r.getOr("event", "NET"), r.get("recipientType"),
                    r.get("recipientId"), r.get("commType"), Long.parseLong(r.get("calcAmount")),
                    r.has("limitCut") ? Long.parseLong(r.get("limitCut")) : null,
                    r.has("closeYm") ? r.get("closeYm") : null));
        }
        return out;
    }

    private static List<ExpectedLedger> loadExpectedLedgers(Path dir) {
        List<ExpectedLedger> out = new ArrayList<>();
        for (GoldenCsv.Row r : GoldenCsv.readTable(dir.resolve("expected_ledger.csv"))) {
            out.add(new ExpectedLedger(r.get("policyNo"), r.get("agentId"),
                    r.has("limitAmount") ? Long.parseLong(r.get("limitAmount")) : null,
                    r.has("accumPaid") ? Long.parseLong(r.get("accumPaid")) : null,
                    r.has("available") ? Long.parseLong(r.get("available")) : null,
                    r.has("fyStart") ? date(r.get("fyStart")) : null,
                    r.has("fyEnd") ? date(r.get("fyEnd")) : null));
        }
        return out;
    }

    private static List<ExpectedSchedule> loadExpectedSchedule(Path dir) {
        List<ExpectedSchedule> out = new ArrayList<>();
        for (GoldenCsv.Row r : GoldenCsv.readTable(dir.resolve("expected_schedule.csv"))) {
            out.add(new ExpectedSchedule(r.get("dueYm"), Long.parseLong(r.get("amount")),
                    r.get("status"), r.getOr("payCondition", "")));
        }
        return out;
    }

    // ---- 파싱 헬퍼 ----

    static LocalDate date(String s) {
        return LocalDate.parse(s.trim());
    }

    static boolean parseBool(String s) {
        String v = s.trim().toLowerCase();
        return v.equals("true") || v.equals("y") || v.equals("yes") || v.equals("1");
    }
}
