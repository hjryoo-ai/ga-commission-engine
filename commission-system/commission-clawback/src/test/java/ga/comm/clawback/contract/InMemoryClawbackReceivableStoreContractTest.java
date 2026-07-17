package ga.comm.clawback.contract;

import ga.comm.clawback.ClawbackReceivableStore;
import ga.comm.clawback.fixture.InMemoryClawbackReceivableStore;

import java.util.concurrent.atomic.AtomicLong;

/** 인메모리 레퍼런스 구현이 계약을 만족함을 증명한다 — Oracle 어댑터는 같은 스위트를 상속한다. */
class InMemoryClawbackReceivableStoreContractTest extends ClawbackReceivableStoreContract {

    private final InMemoryClawbackReceivableStore store = new InMemoryClawbackReceivableStore();
    private final AtomicLong calcIdSeq = new AtomicLong(0);

    @Override
    protected ClawbackReceivableStore store() {
        return store;
    }

    @Override
    protected long aCalcId() {
        return calcIdSeq.incrementAndGet(); // 인메모리는 FK 없음
    }
}
