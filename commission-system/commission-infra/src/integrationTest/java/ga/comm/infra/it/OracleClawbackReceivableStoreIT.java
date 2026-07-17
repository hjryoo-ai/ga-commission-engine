package ga.comm.infra.it;

import ga.comm.clawback.ClawbackReceivableStore;
import ga.comm.clawback.contract.ClawbackReceivableStoreContract;
import ga.comm.infra.OraclePersistence;
import org.junit.jupiter.api.BeforeEach;

import java.util.function.Supplier;

/** Oracle 어댑터가 인메모리 레퍼런스와 같은 계약을 만족함을 증명한다 (§8.7). */
class OracleClawbackReceivableStoreIT extends ClawbackReceivableStoreContract {

    private final OraclePersistence persistence = OracleTestSupport.persistence();

    @BeforeEach
    void clean() {
        OracleTestSupport.cleanAll();
    }

    @Override
    protected ClawbackReceivableStore store() {
        return persistence.clawbackReceivableStore();
    }

    @Override
    protected long aCalcId() {
        return OracleTestSupport.newCalcId();
    }

    @Override
    protected <T> T inTx(Supplier<T> work) {
        return persistence.inTx(work);
    }
}
