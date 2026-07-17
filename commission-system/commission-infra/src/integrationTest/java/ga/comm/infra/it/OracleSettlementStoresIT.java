package ga.comm.infra.it;

import ga.comm.infra.OraclePersistence;
import ga.comm.settlement.AdjustmentService;
import ga.comm.settlement.AgentSettlementStore;
import ga.comm.settlement.SettleCloseStore;
import ga.comm.settlement.contract.AdjustmentStoreContract;
import ga.comm.settlement.contract.AgentSettlementStoreContract;
import ga.comm.settlement.contract.SettleCloseStoreContract;
import org.junit.jupiter.api.BeforeEach;

import java.util.function.Supplier;

/** Oracle 어댑터가 인메모리 레퍼런스와 같은 계약을 만족함을 증명한다 (§8.7). */
class OracleSettlementStoresIT {

    static class SettleClose extends SettleCloseStoreContract {
        private final OraclePersistence persistence = OracleTestSupport.persistence();

        @BeforeEach
        void clean() {
            OracleTestSupport.cleanAll();
        }

        @Override
        protected SettleCloseStore store() {
            return persistence.settleCloseStore();
        }

        @Override
        protected <T> T inTx(Supplier<T> work) {
            return persistence.inTx(work);
        }
    }

    static class AgentSettlement extends AgentSettlementStoreContract {
        private final OraclePersistence persistence = OracleTestSupport.persistence();

        @BeforeEach
        void clean() {
            OracleTestSupport.cleanAll();
        }

        @Override
        protected AgentSettlementStore store() {
            return persistence.agentSettlementStore();
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

    static class Adjustment extends AdjustmentStoreContract {
        private final OraclePersistence persistence = OracleTestSupport.persistence();

        @BeforeEach
        void clean() {
            OracleTestSupport.cleanAll();
        }

        @Override
        protected AdjustmentService.AdjustmentStore store() {
            return persistence.adjustmentStore();
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
}
