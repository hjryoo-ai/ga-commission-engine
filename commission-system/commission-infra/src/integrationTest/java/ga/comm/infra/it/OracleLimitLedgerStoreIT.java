package ga.comm.infra.it;

import ga.comm.infra.OraclePersistence;
import ga.comm.limit.LimitLedgerStore;
import ga.comm.limit.contract.LimitLedgerStoreContract;
import org.junit.jupiter.api.BeforeEach;

import java.util.function.Supplier;

/**
 * Oracle 어댑터가 인메모리 레퍼런스와 같은 계약(전기 순서 = posting_seq 정렬)을 만족함을
 * 증명한다 (§8.7). 락 대기·경합은 {@link OracleLimitRaceIT}가 별도로 증명한다.
 */
class OracleLimitLedgerStoreIT extends LimitLedgerStoreContract {

    private final OraclePersistence persistence = OracleTestSupport.persistence();

    @BeforeEach
    void clean() {
        OracleTestSupport.cleanAll();
    }

    @Override
    protected LimitLedgerStore store() {
        return persistence.limitLedgerStore();
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
