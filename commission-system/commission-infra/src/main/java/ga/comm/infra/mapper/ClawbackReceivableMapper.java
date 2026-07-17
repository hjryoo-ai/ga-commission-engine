package ga.comm.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** CLAWBACK_RECEIVABLE(+OFFSET_HIST) 매퍼 (§5.8). */
public interface ClawbackReceivableMapper {

    class Row {
        public Long receivableId;
        public String agentId;
        public Long originCalcId;
        public long amount;
        public long remaining;
        public String status;
    }

    @Insert("""
            INSERT INTO CLAWBACK_RECEIVABLE (agent_id, origin_calc_id, amount, remaining, status)
            VALUES (#{agentId}, #{originCalcId}, #{amount}, #{remaining}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "receivableId", keyColumn = "RECEIVABLE_ID")
    int insert(Row row);

    /** 상계 가능한(OPEN/OFFSET) 채권 — 오래된 것부터 (생성 순서 = receivable_id 순). */
    @Select("""
            SELECT receivable_id, agent_id, origin_calc_id, amount, remaining, status
              FROM CLAWBACK_RECEIVABLE
             WHERE agent_id = #{agentId} AND status IN ('OPEN', 'OFFSET')
             ORDER BY receivable_id
            """)
    List<Row> findOffsettable(String agentId);

    @Update("UPDATE CLAWBACK_RECEIVABLE SET remaining = #{remaining}, status = #{status} "
            + "WHERE receivable_id = #{receivableId}")
    int updateState(@Param("receivableId") long receivableId, @Param("remaining") long remaining,
                    @Param("status") String status);

    @Insert("INSERT INTO CLAWBACK_OFFSET_HIST (receivable_id, calc_id, amount) "
            + "VALUES (#{receivableId}, #{calcId}, #{amount})")
    int insertOffsetHist(@Param("receivableId") long receivableId, @Param("calcId") long calcId,
                         @Param("amount") long amount);
}
