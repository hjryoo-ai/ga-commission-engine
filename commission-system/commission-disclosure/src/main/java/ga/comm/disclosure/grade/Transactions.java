package ga.comm.disclosure.grade;

import java.util.function.Supplier;

/** 트랜잭션 경계(운영: OraclePersistence.inTx). 채번 잠금과 스냅샷 저장을 한 단위로 묶는다. */
@FunctionalInterface
public interface Transactions {

    <T> T inTx(Supplier<T> work);
}
