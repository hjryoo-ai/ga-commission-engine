package ga.comm.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** AGENT_SETTLEMENT(+CALC) 매퍼 — 마감 정산 내역, 지급 배치의 입력 (V7). */
public interface AgentSettlementMapper {

    class Row {
        public Long settlementId;
        public String closeYm;
        public int runSeq;
        public String recipientType;
        public String recipientId;
        public long grossNet;
        public long receivableOffset;
        public long carriedReceivable;
        public long payable;
    }

    @Insert("""
            INSERT INTO AGENT_SETTLEMENT (close_ym, run_seq, recipient_type, recipient_id, gross_net,
                receivable_offset, carried_receivable, payable)
            VALUES (#{closeYm}, #{runSeq}, #{recipientType}, #{recipientId}, #{grossNet},
                #{receivableOffset}, #{carriedReceivable}, #{payable})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "settlementId", keyColumn = "SETTLEMENT_ID")
    int insert(Row row);

    @Insert("INSERT INTO AGENT_SETTLEMENT_CALC (settlement_id, calc_seq, calc_id) "
            + "VALUES (#{settlementId}, #{calcSeq}, #{calcId})")
    int insertCalc(@Param("settlementId") long settlementId, @Param("calcSeq") long calcSeq,
                   @Param("calcId") long calcId);

    @Select("""
            SELECT settlement_id, close_ym, run_seq, recipient_type, recipient_id, gross_net,
                   receivable_offset, carried_receivable, payable
              FROM AGENT_SETTLEMENT
             WHERE close_ym = #{closeYm}
             ORDER BY settlement_id
            """)
    List<Row> findByCloseYm(String closeYm);

    @Select("SELECT calc_id FROM AGENT_SETTLEMENT_CALC WHERE settlement_id = #{settlementId} "
            + "ORDER BY calc_seq")
    List<Long> calcIds(long settlementId);
}
