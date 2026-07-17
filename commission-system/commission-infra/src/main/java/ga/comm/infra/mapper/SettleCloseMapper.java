package ga.comm.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** SETTLE_CLOSE 매퍼 (§5.9). */
public interface SettleCloseMapper {

    @Select("SELECT status FROM SETTLE_CLOSE WHERE close_ym = #{closeYm}")
    String stateOf(String closeYm);

    @Select("SELECT force_reason FROM SETTLE_CLOSE WHERE close_ym = #{closeYm}")
    String forceReasonOf(String closeYm);

    // force_reason은 사유가 주어졌을 때만 기록하고(NVL), 일반 전이(null)는 기존 사유를 보존한다.
    @Insert("""
            MERGE INTO SETTLE_CLOSE t
            USING (SELECT #{closeYm} AS close_ym FROM dual) s
               ON (t.close_ym = s.close_ym)
             WHEN MATCHED THEN UPDATE
                  SET t.status = #{status}, t.closed_at = CURRENT_TIMESTAMP, t.closed_by = #{by},
                      t.force_reason = NVL(#{forceReason,jdbcType=VARCHAR}, t.force_reason)
             WHEN NOT MATCHED THEN
                  INSERT (close_ym, status, closed_at, closed_by, force_reason)
                  VALUES (#{closeYm}, #{status}, CURRENT_TIMESTAMP, #{by},
                          #{forceReason,jdbcType=VARCHAR})
            """)
    int upsert(@Param("closeYm") String closeYm, @Param("status") String status,
               @Param("by") String by, @Param("forceReason") String forceReason);
}
