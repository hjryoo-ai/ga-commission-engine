package ga.comm.deferral.contract;

import ga.comm.deferral.DeferralScheduleStore;
import ga.comm.deferral.fixture.InMemoryDeferralScheduleStore;

import java.util.concurrent.atomic.AtomicLong;

/** 인메모리 레퍼런스 구현이 계약을 만족함을 증명한다 — Oracle 어댑터는 같은 스위트를 상속한다. */
class InMemoryDeferralScheduleStoreContractTest extends DeferralScheduleStoreContract {

    private final InMemoryDeferralScheduleStore store = new InMemoryDeferralScheduleStore();
    private final AtomicLong calcIdSeq = new AtomicLong(0);

    @Override
    protected DeferralScheduleStore store() {
        return store;
    }

    @Override
    protected long aCalcId() {
        return calcIdSeq.incrementAndGet(); // 인메모리는 FK 없음
    }
}
