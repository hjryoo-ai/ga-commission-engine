package ga.comm.infra.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/** 설계사 마스터 읽기(Phase E1 — AgentSnapshot 부트스트랩). 읽기만 — 엔진에는 마스터 쓰기 경로가 없다. */
public interface AgentMasterMapper {

    class Row {
        public String agentId;
        public String agentName;
        public LocalDate appointedOn;
        public LocalDate dismissedOn;
    }

    @Select("SELECT agent_id FROM AGENT_MST ORDER BY agent_id")
    List<String> agentIds();

    @Select("SELECT agent_id, agent_name, appointed_on, dismissed_on FROM AGENT_MST WHERE agent_id = #{agentId}")
    Row find(@Param("agentId") String agentId);

    /** 기준일에 유효한 등급·소속 이력의 가장 늦은 시작일. */
    @Select("""
            SELECT MAX(apply_from) FROM (
                SELECT apply_from FROM AGENT_GRADE_HIST
                 WHERE agent_id = #{agentId} AND apply_from <= #{asOf} AND apply_to >= #{asOf}
                UNION ALL
                SELECT apply_from FROM AGENT_ORG_HIST
                 WHERE agent_id = #{agentId} AND apply_from <= #{asOf} AND apply_to >= #{asOf}
            )
            """)
    LocalDate latestHistoryFrom(@Param("agentId") String agentId, @Param("asOf") LocalDate asOf);
}
