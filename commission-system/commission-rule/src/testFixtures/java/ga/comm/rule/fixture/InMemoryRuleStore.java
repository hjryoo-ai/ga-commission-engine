package ga.comm.rule.fixture;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.type.Direction;
import ga.comm.domain.type.EventType;
import ga.comm.rule.AmbiguousRuleException;
import ga.comm.rule.RuleRepository;
import ga.comm.rule.admin.CommRateAdminStore;
import ga.comm.rule.admin.RuleChangeEntry;
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
import ga.comm.rule.model.RateKey;
import ga.comm.rule.model.RateStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 인메모리 룰 저장소 — 테스트/시뮬레이션용.
 * {@link RuleRepository}(조회)와 {@link CommRateAdminStore}(승인 워크플로)를 함께 구현한다.
 */
public class InMemoryRuleStore implements RuleRepository, CommRateAdminStore {

    private final List<CommRateRule> rates = new ArrayList<>();
    private final List<CommTypeAttr> commTypeAttrs = new ArrayList<>();
    private final List<PayoutRateRule> payoutRates = new ArrayList<>();
    private final List<LimitRule> limitRules = new ArrayList<>();
    private final List<DeferralCurve> deferralCurves = new ArrayList<>();
    private final List<ClawbackTable> clawbackTables = new ArrayList<>();
    private final List<OrgOverrideRate> orgOverrideRates = new ArrayList<>();
    private final List<RuleChangeEntry> changeLog = new ArrayList<>();
    private final AtomicLong rateIdSeq = new AtomicLong(0);

    // ---- 픽스처 적재 ----

    public InMemoryRuleStore addActiveRate(CommRateRule rule) {
        rates.add(rule.withStatus(RateStatus.ACTIVE));
        return this;
    }

    public InMemoryRuleStore addCommTypeAttr(CommTypeAttr attr) {
        commTypeAttrs.add(attr);
        return this;
    }

    public InMemoryRuleStore addPayoutRate(PayoutRateRule rule) {
        payoutRates.add(rule);
        return this;
    }

    public InMemoryRuleStore addLimitRule(LimitRule rule) {
        limitRules.add(rule);
        return this;
    }

    public InMemoryRuleStore addDeferralCurve(DeferralCurve curve) {
        deferralCurves.add(curve);
        return this;
    }

    public InMemoryRuleStore addClawbackTable(ClawbackTable table) {
        clawbackTables.add(table);
        return this;
    }

    public InMemoryRuleStore addOrgOverrideRate(OrgOverrideRate rate) {
        orgOverrideRates.add(rate);
        return this;
    }

    // ---- RuleRepository ----

    @Override
    public Optional<CommRateRule> findRate(Direction direction, InsurerCode insurerCd, ProductKey productKey,
                                           CommTypeCode commType, Integer installmentNo, LocalDate baseDate) {
        List<CommRateRule> hits = rates.stream()
                .filter(r -> r.status() == RateStatus.ACTIVE)
                .filter(r -> r.key().equals(new RateKey(direction, insurerCd, productKey, commType, installmentNo)))
                .filter(r -> r.period().contains(baseDate))
                .toList();
        return unique(hits, "COMM_RATE " + commType + " 회차=" + installmentNo + " 기준일=" + baseDate);
    }

    @Override
    public Optional<CommTypeAttr> findCommTypeAttr(CommTypeCode commType, LocalDate baseDate) {
        return resolve(commTypeAttrs, a -> a.commType().equals(commType), CommTypeAttr::period,
                baseDate, "COMM_TYPE_MST " + commType);
    }

    @Override
    public Optional<PayoutRateRule> findPayoutRate(String gradeCd, CommTypeCode commType, LocalDate baseDate) {
        return resolve(payoutRates,
                p -> p.gradeCd().equals(gradeCd) && p.commType().equals(commType),
                PayoutRateRule::period, baseDate, "AGENT_PAYOUT_RATE " + gradeCd + "/" + commType);
    }

    @Override
    public Optional<LimitRule> findLimitRule(ChannelType channelType, LocalDate contractDate) {
        return resolve(limitRules, r -> r.channelType() == channelType, LimitRule::period,
                contractDate, "LIMIT_RULE " + channelType);
    }

    @Override
    public Optional<DeferralCurve> findDeferralCurve(LocalDate contractDate) {
        return resolve(deferralCurves, c -> true, DeferralCurve::period,
                contractDate, "DEFERRAL_CURVE");
    }

    @Override
    public Optional<ClawbackTable> findClawbackTable(ProductKey productKey, EventType eventType,
                                                     LocalDate contractDate) {
        Optional<ClawbackTable> productSpecific = resolve(clawbackTables,
                t -> Objects.equals(t.productKey(), productKey.value()) && t.eventType() == eventType,
                ClawbackTable::period, contractDate, "CLAWBACK_RULE " + productKey + "/" + eventType);
        if (productSpecific.isPresent()) {
            return productSpecific;
        }
        return resolve(clawbackTables,
                t -> t.productKey() == null && t.eventType() == eventType,
                ClawbackTable::period, contractDate, "CLAWBACK_RULE 공통/" + eventType);
    }

    @Override
    public Optional<OrgOverrideRate> findOrgOverrideRate(OrgLevel orgLevel, CommTypeCode commType,
                                                         LocalDate baseDate) {
        return resolve(orgOverrideRates,
                r -> r.orgLevel() == orgLevel && r.commType().equals(commType),
                OrgOverrideRate::period, baseDate, "ORG_OVERRIDE_RATE " + orgLevel + "/" + commType);
    }

    // ---- CommRateAdminStore ----

    @Override
    public long nextRateId() {
        return rateIdSeq.incrementAndGet();
    }

    @Override
    public void insert(CommRateRule rule) {
        if (rates.stream().anyMatch(r -> r.rateId() == rule.rateId())) {
            throw new IllegalStateException("이미 존재하는 rateId: " + rule.rateId());
        }
        rates.add(rule);
    }

    @Override
    public void replace(CommRateRule rule) {
        replace(rule, "system");
    }

    @Override
    public void replace(CommRateRule rule, String changedBy) {
        for (int i = 0; i < rates.size(); i++) {
            if (rates.get(i).rateId() == rule.rateId()) {
                // 변경 감사(§6.6): 교체 전후 diff를 이력으로 — 저장소 계층에서 기록해
                // 누락이 구조적으로 불가능하게 한다 (Oracle 어댑터와 동일 규약).
                RuleChangeEntry.diff(rates.get(i), rule, changedBy).ifPresent(changeLog::add);
                rates.set(i, rule);
                return;
            }
        }
        throw new IllegalStateException("존재하지 않는 rateId: " + rule.rateId());
    }

    @Override
    public List<RuleChangeEntry> changeHistory(long rateId) {
        return changeLog.stream().filter(e -> e.rateId() == rateId).toList();
    }

    @Override
    public Optional<CommRateRule> findById(long rateId) {
        return rates.stream().filter(r -> r.rateId() == rateId).findFirst();
    }

    @Override
    public List<CommRateRule> findByKey(RateKey key) {
        return rates.stream().filter(r -> r.key().equals(key)).toList();
    }

    // ---- 내부 ----

    private <T> Optional<T> resolve(List<T> all, java.util.function.Predicate<T> keyMatch,
                                    Function<T, EffectivePeriod> periodOf, LocalDate baseDate, String desc) {
        List<T> hits = all.stream()
                .filter(keyMatch)
                .filter(t -> periodOf.apply(t).contains(baseDate))
                .toList();
        return unique(hits, desc + " 기준일=" + baseDate);
    }

    private <T> Optional<T> unique(List<T> hits, String desc) {
        if (hits.size() > 1) {
            throw new AmbiguousRuleException("유효 버전이 " + hits.size() + "건입니다: " + desc);
        }
        return hits.stream().findFirst();
    }
}
