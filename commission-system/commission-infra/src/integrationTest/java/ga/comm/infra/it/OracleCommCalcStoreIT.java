package ga.comm.infra.it;

import ga.comm.calc.contract.CommCalcStoreContract;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.infra.OraclePersistence;
import org.junit.jupiter.api.BeforeEach;

import java.util.function.Supplier;

/** Oracle 어댑터가 인메모리 레퍼런스와 같은 계약(불변 원장)을 만족함을 증명한다 (§8.7). */
class OracleCommCalcStoreIT extends CommCalcStoreContract {

    private final OraclePersistence persistence = OracleTestSupport.persistence();

    @BeforeEach
    void clean() {
        OracleTestSupport.cleanAll();
    }

    @Override
    protected CommCalcStore store() {
        return persistence.commCalcStore();
    }

    @Override
    protected long anEventId() {
        return OracleTestSupport.newEventId();
    }

    @Override
    protected <T> T inTx(Supplier<T> work) {
        return persistence.inTx(work);
    }
}
