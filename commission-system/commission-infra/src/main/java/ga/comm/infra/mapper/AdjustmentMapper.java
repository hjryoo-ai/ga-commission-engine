package ga.comm.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** ADJUSTMENT 매퍼 (§5.9) — 마감 후 정정의 익월 귀속 기록. INSERT와 귀속월 조회(E1 — 마감 확정 이벤트의 정정 합)만 존재한다. */
public interface AdjustmentMapper {

    class Row {
        public Long adjId;
        public Long targetCalcId;
        public String reason;
        public long amount;
        public String closeYm;
        public String approvedBy;
    }

    @Insert("""
            INSERT INTO ADJUSTMENT (target_calc_id, reason, amount, close_ym, approved_by)
            VALUES (#{targetCalcId}, #{reason}, #{amount}, #{closeYm}, #{approvedBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "adjId", keyColumn = "ADJ_ID")
    int insert(Row row);

    @Select("""
            SELECT adj_id AS adjId, target_calc_id AS targetCalcId, reason, amount, close_ym AS closeYm,
                   approved_by AS approvedBy
              FROM ADJUSTMENT
             WHERE close_ym = #{closeYm}
             ORDER BY adj_id
            """)
    List<Row> findByCloseYm(String closeYm);
}
