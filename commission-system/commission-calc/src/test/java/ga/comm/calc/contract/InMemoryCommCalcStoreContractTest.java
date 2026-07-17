package ga.comm.calc.contract;

import ga.comm.calc.fixture.InMemoryCommCalcStore;
import ga.comm.calc.store.CommCalcStore;

import java.util.concurrent.atomic.AtomicLong;

/** 인메모리 레퍼런스 구현이 계약을 만족함을 증명한다 — Oracle 어댑터는 같은 스위트를 상속한다. */
class InMemoryCommCalcStoreContractTest extends CommCalcStoreContract {

    private final InMemoryCommCalcStore store = new InMemoryCommCalcStore();
    private final AtomicLong eventIdSeq = new AtomicLong(0);

    @Override
    protected CommCalcStore store() {
        return store;
    }

    @Override
    protected long anEventId() {
        return eventIdSeq.incrementAndGet(); // 인메모리는 FK 없음
    }
}
