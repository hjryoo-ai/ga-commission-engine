package ga.comm.infra.it;

import ga.comm.deferral.DeferralScheduleStore;
import ga.comm.deferral.contract.DeferralScheduleStoreContract;
import ga.comm.infra.OraclePersistence;
import org.junit.jupiter.api.BeforeEach;

import java.util.function.Supplier;

/** Oracle 어댑터가 인메모리 레퍼런스와 같은 계약을 만족함을 증명한다 (§8.7). */
class OracleDeferralScheduleStoreIT extends DeferralScheduleStoreContract {

    private final OraclePersistence persistence = OracleTestSupport.persistence();

    @BeforeEach
    void clean() {
        OracleTestSupport.cleanAll();
    }

    @Override
    protected DeferralScheduleStore store() {
        return persistence.deferralScheduleStore();
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
