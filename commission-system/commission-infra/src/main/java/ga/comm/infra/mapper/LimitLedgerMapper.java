package ga.comm.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * LIMIT_LEDGER(+DTL) 매퍼 (§5.6, §6.1.6).
 * 락 규약: 계산 트랜잭션은 {@link #lockByKey}(SELECT FOR UPDATE)로 원장을 획득한 뒤
 * 게이트 판정→저장 후 훅 전기→커밋까지 락을 유지한다. posting_seq는 락 보유 상태에서만
 * MAX+1로 채번한다 (부록 B-5, B-8).
 */
public interface LimitLedgerMapper {

    String COLUMNS = """
            ledger_id, policy_no, agent_id, contract_date, fy_start, fy_end,
            monthly_premium, limit_multiple, limit_amount, accum_paid, rule_version_id
            """;

    class Row {
        public Long ledgerId;
        public String policyNo;
        public String agentId;
        public LocalDate contractDate;
        public LocalDate fyStart;
        public LocalDate fyEnd;
        public long monthlyPremium;
        public BigDecimal limitMultiple;
        public long limitAmount;
        public long accumPaid;
        public long ruleVersionId;
    }

    class PostingRow {
        public long postingSeq;
        public long calcId;
        public long amount;
    }

    @Select("SELECT " + COLUMNS + " FROM LIMIT_LEDGER "
            + "WHERE policy_no = #{policyNo} AND agent_id = #{agentId} FOR UPDATE")
    Row lockByKey(@Param("policyNo") String policyNo, @Param("agentId") String agentId);

    @Insert("""
            INSERT INTO LIMIT_LEDGER (policy_no, agent_id, contract_date, fy_start, fy_end,
                monthly_premium, limit_multiple, limit_amount, accum_paid, rule_version_id)
            VALUES (#{policyNo}, #{agentId}, #{contractDate}, #{fyStart}, #{fyEnd},
                #{monthlyPremium}, #{limitMultiple}, #{limitAmount}, #{accumPaid}, #{ruleVersionId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "ledgerId", keyColumn = "LEDGER_ID")
    int insert(Row row);

    @Update("""
            UPDATE LIMIT_LEDGER
               SET monthly_premium = #{monthlyPremium}, limit_amount = #{limitAmount},
                   accum_paid = #{accumPaid}, lock_version = lock_version + 1
             WHERE ledger_id = #{ledgerId}
            """)
    int update(Row row);

    /** 전기 내역 — 정렬 기준은 posting_seq만 (부록 B-8, posted_at 사용 금지). */
    @Select("SELECT posting_seq, calc_id, amount FROM LIMIT_LEDGER_DTL "
            + "WHERE ledger_id = #{ledgerId} ORDER BY posting_seq")
    List<PostingRow> postings(long ledgerId);

    @Select("SELECT NVL(MAX(posting_seq), 0) FROM LIMIT_LEDGER_DTL WHERE ledger_id = #{ledgerId}")
    long maxPostingSeq(long ledgerId);

    @Insert("INSERT INTO LIMIT_LEDGER_DTL (ledger_id, posting_seq, calc_id, amount) "
            + "VALUES (#{ledgerId}, #{postingSeq}, #{calcId}, #{amount})")
    int insertPosting(@Param("ledgerId") long ledgerId, @Param("postingSeq") long postingSeq,
                      @Param("calcId") long calcId, @Param("amount") long amount);

    /** 마감 배치 전수 검증용 — 락 없이 조회한다. */
    @Select("SELECT " + COLUMNS + " FROM LIMIT_LEDGER ORDER BY ledger_id")
    List<Row> findAll();
}
