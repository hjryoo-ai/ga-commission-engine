package ga.comm.rule.contract;

import ga.comm.rule.admin.IncentiveAdminStore;
import ga.comm.rule.fixture.InMemoryIncentiveStore;

/** 인메모리 레퍼런스가 시책 승인 계약을 통과한다 (Oracle 어댑터는 동일 계약을 IT에서 통과). */
class InMemoryIncentiveApprovalContractTest extends IncentiveApprovalContract {

    private final InMemoryIncentiveStore store = new InMemoryIncentiveStore();

    @Override
    protected IncentiveAdminStore adminStore() {
        return store;
    }
}
