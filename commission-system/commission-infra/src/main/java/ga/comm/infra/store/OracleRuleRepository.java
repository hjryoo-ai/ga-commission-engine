package ga.comm.infra.store;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Rate;
import ga.comm.domain.money.RoundingPolicy;
import ga.comm.domain.type.Direction;
import ga.comm.domain.type.EventType;
import ga.comm.infra.mapper.RuleQueryMapper;
import ga.comm.rule.AmbiguousRuleException;
import ga.comm.rule.RuleRepository;
import ga.comm.rule.model.ChannelType;
import ga.comm.rule.model.ClawbackTable;
import ga.comm.rule.model.CommRateRule;
import ga.comm.rule.model.CommTypeAttr;
import ga.comm.rule.model.DeferralCurve;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.LimitRule;
import ga.comm.rule.model.OrgLevel;
import ga.comm.rule.model.OrgOverrideRate;
import ga.comm.rule.model.PayoutRateRule;
import ga.comm.rule.model.RateStatus;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 룰 조회 Oracle 어댑터 (Phase 10b) — 기준일 필수 규약(부록 B-3)의 실체.
 * 유효 버전이 2건 이상이면 침묵 선택 대신 {@link AmbiguousRuleException}(fail-fast, 부록 B-13).
 */
public class OracleRuleRepository implements RuleRepository {

    private final RuleQueryMapper mapper;

    public OracleRuleRepository(RuleQueryMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public Optional<CommRateRule> findRate(Direction direction, InsurerCode insurerCd, ProductKey productKey,
                                           CommTypeCode commType, Integer installmentNo, LocalDate baseDate) {
        List<RuleQueryMapper.CommRateRow> rows = mapper.findActiveRates(direction.name(),
                insurerCd.value(), productKey.value(), commType.value(), installmentNo, baseDate);
        return unique(rows, "COMM_RATE " + commType + " 회차=" + installmentNo + " 기준일=" + baseDate)
                .map(OracleRuleRepository::toRate);
    }

    @Override
    public Optional<CommTypeAttr> findCommTypeAttr(CommTypeCode commType, LocalDate baseDate) {
        return unique(mapper.findCommTypeAttrs(commType.value(), baseDate),
                "COMM_TYPE_MST " + commType + " 기준일=" + baseDate)
                .map(row -> new CommTypeAttr(new CommTypeCode(row.commType), yn(row.limitIncluded),
                        RoundingPolicy.valueOf(row.roundingPolicy), yn(row.clawbackTarget),
                        EffectivePeriod.of(row.applyFrom, row.applyTo)));
    }

    @Override
    public Optional<PayoutRateRule> findPayoutRate(String gradeCd, CommTypeCode commType, LocalDate baseDate) {
        return unique(mapper.findPayoutRates(gradeCd, commType.value(), baseDate),
                "AGENT_PAYOUT_RATE " + gradeCd + "/" + commType + " 기준일=" + baseDate)
                .map(row -> new PayoutRateRule(row.gradeCd, new CommTypeCode(row.commType),
                        Rate.of(row.payoutRate), EffectivePeriod.of(row.applyFrom, row.applyTo)));
    }

    @Override
    public Optional<LimitRule> findLimitRule(ChannelType channelType, LocalDate contractDate) {
        return unique(mapper.findLimitRules(channelType.name(), contractDate),
                "LIMIT_RULE " + channelType + " 기준일=" + contractDate)
                .map(row -> new LimitRule(row.ruleId, ChannelType.valueOf(row.channelType),
                        row.limitMultiple, row.fyWindowMonths,
                        ga.comm.rule.model.OverLimitAction.valueOf(row.overLimitAction),
                        yn(row.clawbackRestores), EffectivePeriod.of(row.applyFrom, row.applyTo)));
    }

    @Override
    public Optional<DeferralCurve> findDeferralCurve(LocalDate contractDate) {
        return unique(mapper.findDeferralCurves(contractDate), "DEFERRAL_CURVE 기준일=" + contractDate)
                .map(row -> new DeferralCurve(row.curveId, row.curveName,
                        EffectivePeriod.of(row.applyFrom, row.applyTo),
                        mapper.curvePoints(row.curveId).stream()
                                .map(p -> new DeferralCurve.CurvePoint(p.monthNo, Rate.of(p.pct)))
                                .toList()));
    }

    @Override
    public Optional<ClawbackTable> findClawbackTable(ProductKey productKey, EventType eventType,
                                                     LocalDate contractDate) {
        Optional<ClawbackTable> productSpecific = toClawbackTable(
                mapper.findClawbackRowsForProduct(productKey.value(), eventType.name(), contractDate),
                productKey.value(), eventType, contractDate);
        if (productSpecific.isPresent()) {
            return productSpecific;
        }
        return toClawbackTable(mapper.findClawbackRowsCommon(eventType.name(), contractDate),
                null, eventType, contractDate);
    }

    @Override
    public Optional<OrgOverrideRate> findOrgOverrideRate(OrgLevel orgLevel, CommTypeCode commType,
                                                         LocalDate baseDate) {
        return unique(mapper.findOrgOverrideRates(orgLevel.name(), commType.value(), baseDate),
                "ORG_OVERRIDE_RATE " + orgLevel + "/" + commType + " 기준일=" + baseDate)
                .map(row -> new OrgOverrideRate(OrgLevel.valueOf(row.orgLevel),
                        new CommTypeCode(row.commType), Rate.of(row.overrideRate),
                        EffectivePeriod.of(row.applyFrom, row.applyTo)));
    }

    /**
     * CLAWBACK_RULE 행 집합 → 환수 테이블. 한 테이블 버전의 구간(band)들은 같은 유효기간을
     * 공유한다는 것이 데이터 규약 — 기간이 갈리면 버전 겹침이므로 Ambiguous.
     */
    private Optional<ClawbackTable> toClawbackTable(List<RuleQueryMapper.ClawbackRow> rows,
                                                    String productKey, EventType eventType,
                                                    LocalDate contractDate) {
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Set<EffectivePeriod> periods = new LinkedHashSet<>();
        for (RuleQueryMapper.ClawbackRow row : rows) {
            periods.add(EffectivePeriod.of(row.applyFrom, row.applyTo));
        }
        if (periods.size() > 1) {
            throw new AmbiguousRuleException("환수 테이블 버전이 겹칩니다: CLAWBACK_RULE "
                    + (productKey == null ? "공통" : productKey) + "/" + eventType
                    + " 기준일=" + contractDate + " 기간=" + periods);
        }
        List<ClawbackTable.Band> bands = rows.stream()
                .map(row -> new ClawbackTable.Band(row.fromInstallment, row.toInstallment,
                        Rate.of(row.clawbackPct)))
                .toList();
        return Optional.of(new ClawbackTable(productKey, eventType, periods.iterator().next(), bands));
    }

    static CommRateRule toRate(RuleQueryMapper.CommRateRow row) {
        return new CommRateRule(row.rateId, Direction.valueOf(row.direction),
                new InsurerCode(row.insurerCd), new ProductKey(row.productKey),
                new CommTypeCode(row.commType), row.installmentNo, Rate.of(row.rate),
                EffectivePeriod.of(row.applyFrom, row.applyTo), row.versionNo,
                RateStatus.valueOf(row.status));
    }

    private static boolean yn(String flag) {
        return "Y".equals(flag);
    }

    private static <T> Optional<T> unique(List<T> hits, String desc) {
        if (hits.size() > 1) {
            throw new AmbiguousRuleException("유효 버전이 " + hits.size() + "건입니다: " + desc);
        }
        return hits.stream().findFirst();
    }
}
