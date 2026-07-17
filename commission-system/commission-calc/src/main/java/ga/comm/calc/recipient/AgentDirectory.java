package ga.comm.calc.recipient;

import ga.comm.domain.id.AgentId;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 설계사 마스터 조회 포트 (등급 이력·소속 이력). 구현: in-memory(테스트), Oracle(infra). */
public interface AgentDirectory {

    Optional<String> gradeOf(AgentId agentId, LocalDate baseDate);

    /** 기준일 시점 소속 조직 체인 (팀 → 지점 → 본부 순서). 소속이 없으면 빈 목록. */
    List<OrgAssignment> orgChainOf(AgentId agentId, LocalDate baseDate);
}
