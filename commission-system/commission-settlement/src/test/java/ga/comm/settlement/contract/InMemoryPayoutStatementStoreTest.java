package ga.comm.settlement.contract;

import ga.comm.settlement.AgentSettlementStore;
import ga.comm.settlement.PayoutStatementStore;
import ga.comm.settlement.fixture.InMemoryAgentSettlementStore;
import ga.comm.settlement.fixture.InMemoryPayoutStatementStore;

class InMemoryPayoutStatementStoreTest extends PayoutStatementStoreContract {

    private final InMemoryPayoutStatementStore store = new InMemoryPayoutStatementStore();
    private final InMemoryAgentSettlementStore settlements = new InMemoryAgentSettlementStore();

    @Override
    protected PayoutStatementStore store() {
        return store;
    }

    @Override
    protected AgentSettlementStore settlements() {
        return settlements;
    }
}
