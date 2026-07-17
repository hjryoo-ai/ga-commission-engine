package ga.comm.rule.contract;

import ga.comm.rule.model.ClawbackTable;
import ga.comm.rule.model.CommRateRule;
import ga.comm.rule.model.CommTypeAttr;
import ga.comm.rule.model.DeferralCurve;
import ga.comm.rule.model.LimitRule;
import ga.comm.rule.model.OrgOverrideRate;
import ga.comm.rule.model.PayoutRateRule;

/**
 * 계약 테스트용 룰 시드 — 인메모리는 저장소 add/insert, Oracle은 SQL INSERT로 구현한다.
 * 시드조차 포트를 거치면 테스트가 구현을 재검증하는 순환이 생기므로 별도 훅으로 분리한다.
 */
public interface RuleSeeder {

    /** 상태(DRAFT/ACTIVE/SUPERSEDED) 그대로 적재한다. */
    void rate(CommRateRule rule);

    void commTypeAttr(CommTypeAttr attr);

    void payoutRate(PayoutRateRule rule);

    void limitRule(LimitRule rule);

    void deferralCurve(DeferralCurve curve);

    void clawbackTable(ClawbackTable table);

    void orgOverrideRate(OrgOverrideRate rate);
}
