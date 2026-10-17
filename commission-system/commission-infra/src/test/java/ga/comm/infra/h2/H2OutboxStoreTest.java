package ga.comm.infra.h2;

import ga.comm.outbox.OutboxStore;
import ga.comm.outbox.contract.OutboxStoreContract;

import java.util.function.Supplier;

/** 공통 V105 위의 Oracle 어댑터가 아웃박스 계약 스위트를 통과한다(H2 티어, E1 C2). */
class H2OutboxStoreTest extends OutboxStoreContract {

    private final OutboxStore store = H2Persistence.get().outboxStore();

    @Override
    protected OutboxStore store() {
        return store;
    }

    @Override
    protected <T> T tx(Supplier<T> work) {
        return H2Persistence.get().inTx(work);
    }
}
