package ga.comm.limit.contract;

import ga.comm.limit.LimitLedgerStore;
import ga.comm.limit.fixture.InMemoryLimitLedgerStore;

import java.util.concurrent.atomic.AtomicLong;

/** 인메모리 레퍼런스 구현이 계약을 만족함을 증명한다 — Oracle 어댑터는 같은 스위트를 상속한다. */
class InMemoryLimitLedgerStoreContractTest extends LimitLedgerStoreContract {

    private final InMemoryLimitLedgerStore store = new InMemoryLimitLedgerStore();
    private final AtomicLong calcIdSeq = new AtomicLong(0);

    @Override
    protected LimitLedgerStore store() {
        return store;
    }

    @Override
    protected long aCalcId() {
        return calcIdSeq.incrementAndGet(); // 인메모리는 FK 없음
    }
}
