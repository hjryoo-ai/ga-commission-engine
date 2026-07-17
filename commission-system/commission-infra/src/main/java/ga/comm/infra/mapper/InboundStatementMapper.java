package ga.comm.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** INBOUND_STATEMENT 매퍼 (V7) — statement_key 유니크 제약이 멱등 저장의 최종 방어선. */
public interface InboundStatementMapper {

    class Row {
        public Long statementId;
        public String statementKey;
        public String insurerCd;
        public String statementYm;
        public String policyNo;
        public String productKey;
        public String commType;
        public Integer installmentNo;
        public long amount;
        public String rawFields;
    }

    @Insert("""
            INSERT INTO INBOUND_STATEMENT (statement_key, insurer_cd, statement_ym, policy_no,
                product_key, comm_type, installment_no, amount, raw_fields)
            VALUES (#{statementKey}, #{insurerCd}, #{statementYm}, #{policyNo},
                #{productKey}, #{commType}, #{installmentNo}, #{amount}, #{rawFields})
            """)
    int insert(Row row);

    @Select("""
            SELECT statement_id, statement_key, insurer_cd, statement_ym, policy_no,
                   product_key, comm_type, installment_no, amount, raw_fields
              FROM INBOUND_STATEMENT
             WHERE statement_ym = #{statementYm} AND insurer_cd = #{insurerCd}
             ORDER BY statement_id
            """)
    List<Row> findByYmAndInsurer(@Param("statementYm") String statementYm,
                                 @Param("insurerCd") String insurerCd);
}
