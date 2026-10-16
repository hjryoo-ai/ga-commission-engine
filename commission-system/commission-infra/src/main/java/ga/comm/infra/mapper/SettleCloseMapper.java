package ga.comm.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/** SETTLE_CLOSE 매퍼 (§5.9). */
public interface SettleCloseMapper {

    @Select("SELECT status FROM SETTLE_CLOSE WHERE close_ym = #{closeYm}")
    String stateOf(String closeYm);

    @Select("SELECT force_reason FROM SETTLE_CLOSE WHERE close_ym = #{closeYm}")
    String forceReasonOf(String closeYm);

    @Select("SELECT closed_at FROM SETTLE_CLOSE WHERE close_ym = #{closeYm}")
    LocalDateTime closedAtOf(String closeYm);

    // force_reason은 사유가 주어졌을 때만 기록하고(NVL), 일반 전이(null)는 기존 사유를 보존한다.
    // closed_at은 CLOSED 전이에서만 호출자의 시각(주입된 시계)으로 쓰고, CLOSING·OPEN 전이는 건드리지 않는다(v1.2.5, E1 Q5).
    @Insert("""
            MERGE INTO SETTLE_CLOSE t
            USING (SELECT #{closeYm} AS close_ym FROM dual) s
               ON (t.close_ym = s.close_ym)
             WHEN MATCHED THEN UPDATE
                  SET t.status = #{status},
                      t.closed_at = CASE WHEN #{status} = 'CLOSED' THEN #{closedAt,jdbcType=TIMESTAMP} ELSE t.closed_at END,
                      t.closed_by = #{by},
                      t.force_reason = NVL(#{forceReason,jdbcType=VARCHAR}, t.force_reason)
             WHEN NOT MATCHED THEN
                  INSERT (close_ym, status, closed_at, closed_by, force_reason)
                  VALUES (#{closeYm}, #{status},
                          CASE WHEN #{status} = 'CLOSED' THEN #{closedAt,jdbcType=TIMESTAMP} END, #{by},
                          #{forceReason,jdbcType=VARCHAR})
            """)
    int upsert(@Param("closeYm") String closeYm, @Param("status") String status,
               @Param("by") String by, @Param("forceReason") String forceReason,
               @Param("closedAt") LocalDateTime closedAt);
}
