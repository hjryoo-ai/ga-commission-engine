package ga.comm.infra.it;

import ga.comm.calc.contract.AgentDirectoryContract;
import ga.comm.calc.recipient.AgentDirectory;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.OrgId;
import ga.comm.infra.OraclePersistence;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.OrgLevel;
import org.junit.jupiter.api.BeforeEach;

import java.sql.Date;
import java.time.LocalDate;
import java.util.function.Supplier;

/** Oracle 어댑터가 등급/소속 이력 계약(변경일 당일 = 신 버전)을 만족함을 증명한다. */
class OracleAgentDirectoryIT extends AgentDirectoryContract {

    private final OraclePersistence persistence = OracleTestSupport.persistence();

    @BeforeEach
    void clean() {
        OracleTestSupport.cleanAll();
    }

    @Override
    protected AgentDirectory directory() {
        return persistence.agentDirectory();
    }

    @Override
    protected <T> T inTx(Supplier<T> work) {
        return persistence.inTx(work);
    }

    @Override
    protected void seedAgent(AgentId agentId, String name, LocalDate appointedOn) {
        JdbcRuleSeeder.execute(OracleTestSupport.dataSource(),
                "INSERT INTO AGENT_MST (agent_id, agent_name, appointed_on) VALUES (?, ?, ?)", ps -> {
                    ps.setString(1, agentId.value());
                    ps.setString(2, name);
                    ps.setDate(3, Date.valueOf(appointedOn));
                });
    }

    @Override
    protected void seedGrade(AgentId agentId, String gradeCd, EffectivePeriod period) {
        JdbcRuleSeeder.execute(OracleTestSupport.dataSource(),
                "INSERT INTO AGENT_GRADE_HIST (agent_id, grade_cd, apply_from, apply_to) VALUES (?, ?, ?, ?)",
                ps -> {
                    ps.setString(1, agentId.value());
                    ps.setString(2, gradeCd);
                    ps.setDate(3, Date.valueOf(period.applyFrom()));
                    ps.setDate(4, Date.valueOf(period.applyTo()));
                });
    }

    @Override
    protected void seedOrg(OrgId orgId, OrgLevel level, OrgId parent) {
        JdbcRuleSeeder.execute(OracleTestSupport.dataSource(),
                "INSERT INTO ORG_MST (org_id, org_name, org_level, parent_org) VALUES (?, ?, ?, ?)", ps -> {
                    ps.setString(1, orgId.value());
                    ps.setString(2, orgId.value() + " 조직");
                    ps.setString(3, level.name());
                    ps.setString(4, parent == null ? null : parent.value());
                });
    }

    @Override
    protected void seedAssignment(AgentId agentId, OrgId orgId, EffectivePeriod period) {
        JdbcRuleSeeder.execute(OracleTestSupport.dataSource(),
                "INSERT INTO AGENT_ORG_HIST (agent_id, org_id, apply_from, apply_to) VALUES (?, ?, ?, ?)",
                ps -> {
                    ps.setString(1, agentId.value());
                    ps.setString(2, orgId.value());
                    ps.setDate(3, Date.valueOf(period.applyFrom()));
                    ps.setDate(4, Date.valueOf(period.applyTo()));
                });
    }
}
