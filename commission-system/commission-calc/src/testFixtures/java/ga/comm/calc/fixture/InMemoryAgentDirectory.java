package ga.comm.calc.fixture;

import ga.comm.calc.recipient.AgentDirectory;
import ga.comm.calc.recipient.OrgAssignment;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.OrgId;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.OrgLevel;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 인메모리 설계사 디렉토리 — 등급/소속 이력을 유효기간 레코드로 보관한다. */
public class InMemoryAgentDirectory implements AgentDirectory {

    private record GradeHist(AgentId agentId, String gradeCd, EffectivePeriod period) {
    }

    private record OrgHist(AgentId agentId, OrgAssignment assignment, EffectivePeriod period) {
    }

    private final List<GradeHist> grades = new ArrayList<>();
    private final List<OrgHist> orgs = new ArrayList<>();

    public InMemoryAgentDirectory withGrade(AgentId agentId, String gradeCd, LocalDate from) {
        grades.add(new GradeHist(agentId, gradeCd, EffectivePeriod.from(from)));
        return this;
    }

    public InMemoryAgentDirectory withGrade(AgentId agentId, String gradeCd, EffectivePeriod period) {
        grades.add(new GradeHist(agentId, gradeCd, period));
        return this;
    }

    /** 소속 체인 등록 (팀 → 지점 → 본부 순). */
    public InMemoryAgentDirectory withOrgChain(AgentId agentId, LocalDate from, OrgAssignment... chain) {
        for (OrgAssignment assignment : chain) {
            orgs.add(new OrgHist(agentId, assignment, EffectivePeriod.from(from)));
        }
        return this;
    }

    public InMemoryAgentDirectory withOrgChain(AgentId agentId, EffectivePeriod period, OrgAssignment... chain) {
        for (OrgAssignment assignment : chain) {
            orgs.add(new OrgHist(agentId, assignment, period));
        }
        return this;
    }

    public static OrgAssignment team(String orgId) {
        return new OrgAssignment(new OrgId(orgId), OrgLevel.TEAM);
    }

    public static OrgAssignment branch(String orgId) {
        return new OrgAssignment(new OrgId(orgId), OrgLevel.BRANCH);
    }

    public static OrgAssignment hq(String orgId) {
        return new OrgAssignment(new OrgId(orgId), OrgLevel.HQ);
    }

    @Override
    public Optional<String> gradeOf(AgentId agentId, LocalDate baseDate) {
        return grades.stream()
                .filter(g -> g.agentId().equals(agentId) && g.period().contains(baseDate))
                .map(GradeHist::gradeCd)
                .findFirst();
    }

    @Override
    public List<OrgAssignment> orgChainOf(AgentId agentId, LocalDate baseDate) {
        return orgs.stream()
                .filter(o -> o.agentId().equals(agentId) && o.period().contains(baseDate))
                .map(OrgHist::assignment)
                .toList();
    }
}
