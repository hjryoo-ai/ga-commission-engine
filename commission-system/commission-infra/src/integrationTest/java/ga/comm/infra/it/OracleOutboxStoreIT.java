package ga.comm.infra.it;

import ga.comm.infra.OraclePersistence;
import ga.comm.outbox.OutboxStore;
import ga.comm.outbox.contract.OutboxStoreContract;

import java.util.function.Supplier;

/** Oracle 아웃박스 어댑터가 인메모리 레퍼런스와 같은 계약을 만족한다(§8.7, E1 C2). */
class OracleOutboxStoreIT extends OutboxStoreContract {

    private final OraclePersistence persistence = OracleTestSupport.persistence();
    private final OutboxStore store = persistence.outboxStore();

    @Override
    protected OutboxStore store() {
        return store;
    }

    @Override
    protected <T> T tx(Supplier<T> work) {
        return persistence.inTx(work);
    }
}
