package ga.comm.infra.it;

import ga.comm.inbound.InboundStatementStore;
import ga.comm.inbound.contract.InboundStatementStoreContract;
import ga.comm.infra.OraclePersistence;
import org.junit.jupiter.api.BeforeEach;

import java.util.function.Supplier;

/** Oracle 어댑터가 인메모리 레퍼런스와 같은 계약(statementKey 멱등)을 만족함을 증명한다 (§8.7). */
class OracleInboundStatementStoreIT extends InboundStatementStoreContract {

    private final OraclePersistence persistence = OracleTestSupport.persistence();

    @BeforeEach
    void clean() {
        OracleTestSupport.cleanAll();
    }

    @Override
    protected InboundStatementStore store() {
        return persistence.inboundStatementStore();
    }

    @Override
    protected <T> T inTx(Supplier<T> work) {
        return persistence.inTx(work);
    }
}
