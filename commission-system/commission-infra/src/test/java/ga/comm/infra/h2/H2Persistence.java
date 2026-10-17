package ga.comm.infra.h2;

import ga.comm.infra.OraclePersistence;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;

/**
 * H2(MODE=Oracle) 위의 Oracle 어댑터 — 빠른 티어에서 공통 마이그레이션(db/migration)만으로 계약 스위트를 돌린다(E1 승인 §3:
 * "H2 티어는 synchronized 인메모리와 공통 V105 둘 다"). Oracle 전용(V106 트리거·IS JSON)은 integrationTest가 본다.
 */
final class H2Persistence {

    private static OraclePersistence persistence;

    private H2Persistence() {
    }

    static synchronized OraclePersistence get() {
        if (persistence == null) {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:e1_outbox;MODE=Oracle;DB_CLOSE_DELAY=-1");
            ds.setUser("sa");
            Flyway.configure().dataSource(ds).load().migrate();
            persistence = new OraclePersistence(ds);
        }
        return persistence;
    }
}
