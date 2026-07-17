package ga.comm.inbound.contract;

import ga.comm.inbound.InboundStatementStore;
import ga.comm.inbound.fixture.InMemoryInboundStatementStore;

/** 인메모리 레퍼런스 구현이 계약을 만족함을 증명한다 — Oracle 어댑터는 같은 스위트를 상속한다. */
class InMemoryInboundStatementStoreContractTest extends InboundStatementStoreContract {

    private final InMemoryInboundStatementStore store = new InMemoryInboundStatementStore();

    @Override
    protected InboundStatementStore store() {
        return store;
    }
}
