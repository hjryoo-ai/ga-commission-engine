package ga.comm.infra.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/** 설계사 등급/소속 이력 매퍼 (Phase 10b, §2.4). 조회는 기준일 필수(부록 B-3). */
public interface AgentDirectoryMapper {

    @Select("""
            SELECT grade_cd
              FROM AGENT_GRADE_HIST
             WHERE agent_id = #{agentId}
               AND apply_from <= #{baseDate} AND apply_to >= #{baseDate}
             ORDER BY apply_from DESC
            """)
    List<String> gradesOf(@Param("agentId") String agentId, @Param("baseDate") LocalDate baseDate);

    class OrgChainRow {
        public String orgId;
        public String orgLevel;
    }

    /** 기준일 시점 소속 체인 — 팀 → 지점 → 본부 순 (설계서 §2.4, AgentDirectory 규약). */
    @Select("""
            SELECT h.org_id, o.org_level
              FROM AGENT_ORG_HIST h
              JOIN ORG_MST o ON o.org_id = h.org_id
             WHERE h.agent_id = #{agentId}
               AND h.apply_from <= #{baseDate} AND h.apply_to >= #{baseDate}
             ORDER BY CASE o.org_level WHEN 'TEAM' THEN 1 WHEN 'BRANCH' THEN 2 WHEN 'HQ' THEN 3 END
            """)
    List<OrgChainRow> orgChainOf(@Param("agentId") String agentId, @Param("baseDate") LocalDate baseDate);
}
