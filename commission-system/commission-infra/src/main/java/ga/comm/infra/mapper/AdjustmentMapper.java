package ga.comm.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;

/** ADJUSTMENT 매퍼 (§5.9) — 마감 후 정정의 익월 귀속 기록. INSERT만 존재한다. */
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
}
