package ga.comm.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** DEFERRAL_SCHEDULE 매퍼 (§5.7). 갱신은 상태 전이 컬럼(status, released_calc_id)만 허용한다. */
public interface DeferralScheduleMapper {

    String COLUMNS = """
            schedule_id, source_calc_id, policy_no, agent_id, due_ym, amount,
            pay_condition, status, curve_version_id, released_calc_id
            """;

    class Row {
        public Long scheduleId;
        public long sourceCalcId;
        public String policyNo;
        public String agentId;
        public String dueYm;
        public long amount;
        public String payCondition;
        public String status;
        public long curveVersionId;
        public Long releasedCalcId;
    }

    @Insert("""
            INSERT INTO DEFERRAL_SCHEDULE (source_calc_id, policy_no, agent_id, due_ym, amount,
                pay_condition, status, curve_version_id, released_calc_id)
            VALUES (#{sourceCalcId}, #{policyNo}, #{agentId}, #{dueYm}, #{amount},
                #{payCondition}, #{status}, #{curveVersionId}, #{releasedCalcId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "scheduleId", keyColumn = "SCHEDULE_ID")
    int insert(Row row);

    @Select("SELECT " + COLUMNS + " FROM DEFERRAL_SCHEDULE "
            + "WHERE due_ym = #{dueYm} AND status = 'SCHEDULED' ORDER BY schedule_id")
    List<Row> findDue(String dueYm);

    @Select("SELECT " + COLUMNS + " FROM DEFERRAL_SCHEDULE "
            + "WHERE policy_no = #{policyNo} AND agent_id = #{agentId} ORDER BY schedule_id")
    List<Row> findByPolicyAndAgent(@Param("policyNo") String policyNo, @Param("agentId") String agentId);

    @Select("SELECT " + COLUMNS + " FROM DEFERRAL_SCHEDULE "
            + "WHERE source_calc_id = #{sourceCalcId} ORDER BY schedule_id")
    List<Row> findBySourceCalcId(long sourceCalcId);

    @Update("UPDATE DEFERRAL_SCHEDULE SET status = #{status}, released_calc_id = #{releasedCalcId} "
            + "WHERE schedule_id = #{scheduleId}")
    int updateTransition(@Param("scheduleId") long scheduleId, @Param("status") String status,
                         @Param("releasedCalcId") Long releasedCalcId);
}
