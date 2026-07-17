package ga.comm.rule.contract;

import ga.comm.rule.admin.CommRateAdminStore;
import ga.comm.rule.fixture.InMemoryRuleStore;

/** 인메모리 레퍼런스 구현이 승인 워크플로 계약(트리밍 감사 포함)을 만족함을 증명한다. */
class InMemoryRateApprovalContractTest extends RateApprovalContract {

    private final InMemoryRuleStore store = new InMemoryRuleStore();

    @Override
    protected CommRateAdminStore adminStore() {
        return store;
    }
}
