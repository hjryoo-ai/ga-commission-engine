package ga.comm.settlement;

import ga.comm.domain.time.CloseYm;

import java.util.List;

/**
 * 마감 리포트 포트 (설계서 §7, Phase 11) — 마감 잡이 CLOSED 전이 후 실행하는 안전망 검출.
 * 발견 항목은 마감을 되돌리지 않는다(비차단) — 관리자 후속 조치 대상으로 표면화한다.
 *
 * <p>구현 3종(§5.2 3차 안전망·§5.5·§6.6): ① 룰 데이터 완결성(필수값 누락·겹침 Ambiguous),
 * ② MAXVALUE 파티션 적재 검출(연 파티션 SPLIT 누락), ③ 승인 경합 감지(ACTIVATE 직후 SUPERSEDE).
 */
public interface CloseReport {

    String name();

    /** 발견 항목 목록 — 비어 있으면 정상. */
    List<String> findings(CloseYm closeYm);
}
