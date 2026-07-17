package ga.comm.infra.store;

import ga.comm.calc.recipient.AgentDirectory;
import ga.comm.calc.recipient.OrgAssignment;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.OrgId;
import ga.comm.infra.mapper.AgentDirectoryMapper;
import ga.comm.rule.model.OrgLevel;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 설계사 등급/소속 이력 Oracle 어댑터 (Phase 10b).
 * 결과 없음은 empty로 반환한다 — 필수 여부 판단과 예외 승격은 호출측(RecipientResolver 등)의 몫
 * (RuleRepository와 같은 규약).
 */
public class OracleAgentDirectory implements AgentDirectory {

    private final AgentDirectoryMapper mapper;

    public OracleAgentDirectory(AgentDirectoryMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public Optional<String> gradeOf(AgentId agentId, LocalDate baseDate) {
        return mapper.gradesOf(agentId.value(), baseDate).stream().findFirst();
    }

    @Override
    public List<OrgAssignment> orgChainOf(AgentId agentId, LocalDate baseDate) {
        return mapper.orgChainOf(agentId.value(), baseDate).stream()
                .map(row -> new OrgAssignment(new OrgId(row.orgId), OrgLevel.valueOf(row.orgLevel)))
                .toList();
    }
}
