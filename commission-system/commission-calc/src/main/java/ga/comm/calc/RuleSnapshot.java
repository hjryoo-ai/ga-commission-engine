package ga.comm.calc;

import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.type.Direction;
import ga.comm.domain.type.EventType;
import ga.comm.rule.RuleRepository;
import ga.comm.rule.model.ChannelType;
import ga.comm.rule.model.ClawbackTable;
import ga.comm.rule.model.CommRateRule;
import ga.comm.rule.model.CommTypeAttr;
import ga.comm.rule.model.DeferralCurve;
import ga.comm.rule.model.LimitRule;
import ga.comm.rule.model.OrgLevel;
import ga.comm.rule.model.OrgOverrideRate;
import ga.comm.rule.model.PayoutRateRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 기준일로 확정된 룰 버전 묶음.
 *
 * <p>모든 조회는 이벤트가 정한 기준일로만 수행된다 — 회차성 룰(요율/지급률/유형속성/오버라이드)은
 * 업무 발생일(event_date), 계약성 룰(한도/분급/환수)은 계약 체결일(contract_date).
 * 조회 성공한 룰은 전부 {@link RuleVersionRef}로 수집되어 COMM_CALC에 박제된다(재현성).
 */
public final class RuleSnapshot {

    private final RuleRepository repo;
    private final PolicyEvent event;
    private final List<RuleVersionRef> used = new ArrayList<>();

    public RuleSnapshot(RuleRepository repo, PolicyEvent event) {
        this.repo = repo;
        this.event = event;
    }

    public Optional<CommRateRule> outboundRate(CommTypeCode commType, Integer installmentNo) {
        Optional<CommRateRule> found = repo.findRate(Direction.OUTBOUND, event.insurerCd(),
                event.productKey(), commType, installmentNo, event.eventDate());
        found.ifPresent(r -> used.add(new RuleVersionRef("COMM_RATE",
                commType.value() + "/inst=" + installmentNo,
                "rateId=" + r.rateId() + ",v=" + r.versionNo())));
        return found;
    }

    public Optional<CommTypeAttr> commTypeAttr(CommTypeCode commType) {
        Optional<CommTypeAttr> found = repo.findCommTypeAttr(commType, event.eventDate());
        found.ifPresent(a -> used.add(new RuleVersionRef("COMM_TYPE_MST", commType.value(),
                "from=" + a.period().applyFrom())));
        return found;
    }

    public Optional<PayoutRateRule> payoutRate(String gradeCd, CommTypeCode commType) {
        Optional<PayoutRateRule> found = repo.findPayoutRate(gradeCd, commType, event.eventDate());
        found.ifPresent(p -> used.add(new RuleVersionRef("AGENT_PAYOUT_RATE",
                gradeCd + "/" + commType.value(), "from=" + p.period().applyFrom())));
        return found;
    }

    /** 한도룰 — 기준일은 계약 체결일. empty = 해당 계약에 1200%룰 미적용. */
    public Optional<LimitRule> limitRule() {
        Optional<LimitRule> found = repo.findLimitRule(ChannelType.GA_TO_AGENT, event.contractDate());
        found.ifPresent(r -> used.add(new RuleVersionRef("LIMIT_RULE",
                ChannelType.GA_TO_AGENT.name(), "ruleId=" + r.ruleId())));
        return found;
    }

    /** 분급 커브 — 기준일은 계약 체결일. empty = 분급 미적용. */
    public Optional<DeferralCurve> deferralCurve() {
        Optional<DeferralCurve> found = repo.findDeferralCurve(event.contractDate());
        found.ifPresent(c -> used.add(new RuleVersionRef("DEFERRAL_CURVE",
                c.curveName(), "curveId=" + c.curveId())));
        return found;
    }

    /** 환수 테이블 — 기준일은 계약 체결일. */
    public Optional<ClawbackTable> clawbackTable(EventType eventType) {
        Optional<ClawbackTable> found = repo.findClawbackTable(event.productKey(), eventType,
                event.contractDate());
        found.ifPresent(t -> used.add(new RuleVersionRef("CLAWBACK_RULE",
                (t.productKey() == null ? "공통" : t.productKey()) + "/" + eventType,
                "from=" + t.period().applyFrom())));
        return found;
    }

    public Optional<OrgOverrideRate> orgOverrideRate(OrgLevel level, CommTypeCode commType) {
        Optional<OrgOverrideRate> found = repo.findOrgOverrideRate(level, commType, event.eventDate());
        found.ifPresent(r -> used.add(new RuleVersionRef("ORG_OVERRIDE_RATE",
                level + "/" + commType.value(), "from=" + r.period().applyFrom())));
        return found;
    }

    /**
     * 스냅샷이 직접 조회하지 않는 룰(예: 시책 마스터 — Step이 별도 저장소로 해석)의 버전 참조를
     * 박제 목록에 편입한다. 재현성(§6.5 replay)을 위해 계산에 <b>실제 사용된</b> 버전만 넣는다.
     */
    public void recordRuleVersion(RuleVersionRef ref) {
        used.add(ref);
    }

    public List<RuleVersionRef> usedRuleVersions() {
        return Collections.unmodifiableList(used);
    }
}
