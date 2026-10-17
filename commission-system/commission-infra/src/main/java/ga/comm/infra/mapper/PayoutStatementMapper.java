package ga.comm.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

/** PAYOUT_STATEMENT 매퍼(V107, Phase E1 P6) — insert와 조회뿐. */
public interface PayoutStatementMapper {

    class Row {
        public String closeYm;
        public int runSeq;
        public String recipientType;
        public String recipientId;
        public long payable;
        public long incomeTax;
        public long localTax;
        public long netPay;
        public OffsetDateTime paidAt;
    }

    /**
     * 같은 트랜잭션에서 방금 남긴 정산 행에 연결한다 — 그 (마감월, 런, 수급자)의 가장 최근 정산 행(IDENTITY 단조, 단일 인스턴스).
     * 정산 행이 없으면 settlement_id가 NULL이라 NOT NULL 위반으로 실패한다(명세만 홀로 남지 않는다).
     */
    @Insert("""
            INSERT INTO PAYOUT_STATEMENT (settlement_id, close_ym, run_seq, recipient_type, recipient_id,
                payable, income_tax, local_tax, net_pay, paid_at)
            SELECT (SELECT MAX(s.settlement_id) FROM AGENT_SETTLEMENT s
                     WHERE s.close_ym = #{closeYm} AND s.run_seq = #{runSeq}
                       AND s.recipient_type = #{recipientType} AND s.recipient_id = #{recipientId}),
                   #{closeYm}, #{runSeq}, #{recipientType}, #{recipientId},
                   #{payable}, #{incomeTax}, #{localTax}, #{netPay}, #{paidAt}
              FROM dual
            """)
    int insert(Row row);

    @Select("""
            SELECT close_ym, run_seq, recipient_type, recipient_id, payable, income_tax, local_tax, net_pay, paid_at
              FROM PAYOUT_STATEMENT
             WHERE close_ym = #{closeYm}
             ORDER BY payout_statement_id
            """)
    List<Row> findByCloseYm(String closeYm);
}
