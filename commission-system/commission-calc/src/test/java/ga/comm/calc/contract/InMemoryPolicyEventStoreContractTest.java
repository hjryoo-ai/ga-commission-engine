package ga.comm.calc.contract;

import ga.comm.calc.fixture.InMemoryPolicyEventStore;
import ga.comm.calc.store.PolicyEventStore;

/** 인메모리 레퍼런스 구현이 계약을 만족함을 증명한다 — Oracle 어댑터는 같은 스위트를 상속한다. */
class InMemoryPolicyEventStoreContractTest extends PolicyEventStoreContract {

    private final InMemoryPolicyEventStore store = new InMemoryPolicyEventStore();

    @Override
    protected PolicyEventStore store() {
        return store;
    }
}
