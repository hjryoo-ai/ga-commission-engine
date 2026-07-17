package ga.comm.calc.contract;

import ga.comm.calc.fixture.InMemoryAgentDirectory;
import ga.comm.calc.recipient.AgentDirectory;
import ga.comm.calc.recipient.OrgAssignment;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.OrgId;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.OrgLevel;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** 인메모리 레퍼런스 구현이 계약을 만족함을 증명한다 — Oracle 어댑터는 같은 스위트를 상속한다. */
class InMemoryAgentDirectoryContractTest extends AgentDirectoryContract {

    private final InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
    private final Map<OrgId, OrgLevel> orgLevels = new HashMap<>();

    @Override
    protected AgentDirectory directory() {
        return directory;
    }

    @Override
    protected void seedAgent(AgentId agentId, String name, LocalDate appointedOn) {
        // 인메모리는 마스터 행이 따로 없다 (FK 없음)
    }

    @Override
    protected void seedGrade(AgentId agentId, String gradeCd, EffectivePeriod period) {
        directory.withGrade(agentId, gradeCd, period);
    }

    @Override
    protected void seedOrg(OrgId orgId, OrgLevel level, OrgId parent) {
        orgLevels.put(orgId, level);
    }

    @Override
    protected void seedAssignment(AgentId agentId, OrgId orgId, EffectivePeriod period) {
        OrgLevel level = orgLevels.get(orgId);
        if (level == null) {
            throw new IllegalStateException("seedOrg 먼저 호출 필요: " + orgId);
        }
        directory.withOrgChain(agentId, period, new OrgAssignment(orgId, level));
    }
}
