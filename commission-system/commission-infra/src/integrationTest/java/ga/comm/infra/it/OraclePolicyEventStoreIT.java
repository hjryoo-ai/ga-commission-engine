package ga.comm.infra.it;

import ga.comm.calc.contract.PolicyEventStoreContract;
import ga.comm.calc.store.PolicyEventStore;
import ga.comm.infra.OraclePersistence;
import org.junit.jupiter.api.BeforeEach;

import java.util.function.Supplier;

/** Oracle 어댑터가 인메모리 레퍼런스와 같은 계약을 만족함을 증명한다 (§8.7). */
class OraclePolicyEventStoreIT extends PolicyEventStoreContract {

    private final OraclePersistence persistence = OracleTestSupport.persistence();

    @BeforeEach
    void clean() {
        OracleTestSupport.cleanAll();
    }

    @Override
    protected PolicyEventStore store() {
        return persistence.policyEventStore();
    }

    @Override
    protected <T> T inTx(Supplier<T> work) {
        return persistence.inTx(work);
    }
}
