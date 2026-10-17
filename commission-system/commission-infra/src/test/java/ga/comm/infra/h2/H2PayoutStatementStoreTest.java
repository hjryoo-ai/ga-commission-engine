package ga.comm.infra.h2;

import ga.comm.settlement.AgentSettlementStore;
import ga.comm.settlement.PayoutStatementStore;
import ga.comm.settlement.contract.PayoutStatementStoreContract;

import java.util.function.Supplier;

/** 공통 V107 위의 지급명세 어댑터가 계약 스위트를 통과한다(H2 티어, E1 P6). */
class H2PayoutStatementStoreTest extends PayoutStatementStoreContract {

    @Override
    protected PayoutStatementStore store() {
        return H2Persistence.get().payoutStatementStore();
    }

    @Override
    protected AgentSettlementStore settlements() {
        return H2Persistence.get().agentSettlementStore();
    }

    @Override
    protected <T> T inTx(Supplier<T> work) {
        return H2Persistence.get().inTx(work);
    }
}
