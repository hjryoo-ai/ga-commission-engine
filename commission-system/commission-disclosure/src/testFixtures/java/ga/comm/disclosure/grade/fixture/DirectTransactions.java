package ga.comm.disclosure.grade.fixture;

import ga.comm.disclosure.grade.Transactions;

import java.util.function.Supplier;

/** 인메모리 테스트용 — 트랜잭션 없이 바로 실행. */
public final class DirectTransactions implements Transactions {

    @Override
    public <T> T inTx(Supplier<T> work) {
        return work.get();
    }
}
