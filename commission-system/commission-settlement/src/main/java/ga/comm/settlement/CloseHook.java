package ga.comm.settlement;

import ga.comm.domain.time.CloseYm;

/**
 * 마감 확정(⑤) 직전에 실행되는 플러그인 훅 — 분급 도래분 RELEASE(②) 등이 여기 연결된다.
 * 훅 목록 자체가 배선(설정)이므로 규정 추가 시 마감 서비스는 수정하지 않는다.
 */
public interface CloseHook {

    String name();

    /** 수행 결과 요약을 반환한다 (마감 리포트에 기록). */
    String beforeConfirm(CloseYm closeYm);
}
