package ga.comm.rule.contract;

import ga.comm.rule.RuleRepository;
import ga.comm.rule.fixture.InMemoryRuleStore;
import ga.comm.rule.model.ClawbackTable;
import ga.comm.rule.model.CommRateRule;
import ga.comm.rule.model.CommTypeAttr;
import ga.comm.rule.model.DeferralCurve;
import ga.comm.rule.model.LimitRule;
import ga.comm.rule.model.OrgOverrideRate;
import ga.comm.rule.model.PayoutRateRule;

/** 인메모리 레퍼런스 구현이 계약을 만족함을 증명한다 — Oracle 어댑터는 같은 스위트를 상속한다. */
class InMemoryRuleRepositoryContractTest extends RuleRepositoryContract {

    private final InMemoryRuleStore store = new InMemoryRuleStore();

    private final RuleSeeder seeder = new RuleSeeder() {
        @Override
        public void rate(CommRateRule rule) {
            store.insert(rule); // 상태 그대로 (DRAFT/SUPERSEDED 검증용)
        }

        @Override
        public void commTypeAttr(CommTypeAttr attr) {
            store.addCommTypeAttr(attr);
        }

        @Override
        public void payoutRate(PayoutRateRule rule) {
            store.addPayoutRate(rule);
        }

        @Override
        public void limitRule(LimitRule rule) {
            store.addLimitRule(rule);
        }

        @Override
        public void deferralCurve(DeferralCurve curve) {
            store.addDeferralCurve(curve);
        }

        @Override
        public void clawbackTable(ClawbackTable table) {
            store.addClawbackTable(table);
        }

        @Override
        public void orgOverrideRate(OrgOverrideRate rate) {
            store.addOrgOverrideRate(rate);
        }
    };

    @Override
    protected RuleRepository repository() {
        return store;
    }

    @Override
    protected RuleSeeder seeder() {
        return seeder;
    }
}
