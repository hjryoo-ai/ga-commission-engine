package ga.comm.settlement.contract;

import ga.comm.settlement.AdjustmentService;
import ga.comm.settlement.AgentSettlementStore;
import ga.comm.settlement.SettleCloseStore;
import ga.comm.settlement.fixture.InMemoryAdjustmentStore;
import ga.comm.settlement.fixture.InMemoryAgentSettlementStore;
import ga.comm.settlement.fixture.InMemorySettleCloseStore;

import java.util.concurrent.atomic.AtomicLong;

/** 인메모리 레퍼런스 구현이 계약을 만족함을 증명한다 — Oracle 어댑터는 같은 스위트를 상속한다. */
class InMemorySettlementStoresContractTest {

    static class SettleClose extends SettleCloseStoreContract {
        private final InMemorySettleCloseStore store = new InMemorySettleCloseStore();

        @Override
        protected SettleCloseStore store() {
            return store;
        }
    }

    static class AgentSettlement extends AgentSettlementStoreContract {
        private final InMemoryAgentSettlementStore store = new InMemoryAgentSettlementStore();
        private final AtomicLong calcIdSeq = new AtomicLong(0);

        @Override
        protected AgentSettlementStore store() {
            return store;
        }

        @Override
        protected long aCalcId() {
            return calcIdSeq.incrementAndGet(); // 인메모리는 FK 없음
        }
    }

    static class Adjustment extends AdjustmentStoreContract {
        private final InMemoryAdjustmentStore store = new InMemoryAdjustmentStore();
        private final AtomicLong calcIdSeq = new AtomicLong(0);

        @Override
        protected AdjustmentService.AdjustmentStore store() {
            return store;
        }

        @Override
        protected long aCalcId() {
            return calcIdSeq.incrementAndGet(); // 인메모리는 FK 없음
        }
    }
}
