package ga.comm.infra.it;

import ga.comm.infra.OraclePersistence;
import ga.comm.rule.RuleRepository;
import ga.comm.rule.contract.RuleRepositoryContract;
import ga.comm.rule.contract.RuleSeeder;
import org.junit.jupiter.api.BeforeEach;

import java.util.function.Supplier;

/** Oracle 어댑터가 인메모리 레퍼런스와 같은 계약(유효기간 경계·Ambiguous·기준일 필수)을 만족함을 증명한다. */
class OracleRuleRepositoryIT extends RuleRepositoryContract {

    private final OraclePersistence persistence = OracleTestSupport.persistence();
    private final RuleSeeder seeder = new JdbcRuleSeeder(OracleTestSupport.dataSource());

    @BeforeEach
    void clean() {
        OracleTestSupport.cleanAll();
    }

    @Override
    protected RuleRepository repository() {
        return persistence.ruleRepository();
    }

    @Override
    protected RuleSeeder seeder() {
        return seeder;
    }

    @Override
    protected <T> T inTx(Supplier<T> work) {
        return persistence.inTx(work);
    }
}
