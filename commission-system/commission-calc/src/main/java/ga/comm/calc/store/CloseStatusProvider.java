package ga.comm.calc.store;

import ga.comm.domain.time.CloseYm;

/**
 * 마감 상태 조회 포트 — 정정 귀속월 결정에 사용한다 (설계서 §6.4).
 * 마감(CLOSED)된 월의 정정은 첫 OPEN 월(익월 이후)로 귀속된다. 과거 마감 숫자는 절대 바뀌지 않는다.
 */
@FunctionalInterface
public interface CloseStatusProvider {

    boolean isClosed(CloseYm closeYm);

    /** 원 귀속월이 마감됐으면 이후 첫 미마감 월을 반환. */
    default CloseYm attributionFor(CloseYm original) {
        CloseYm ym = original;
        int guard = 0;
        while (isClosed(ym)) {
            ym = ym.next();
            if (++guard > 1200) {
                throw new IllegalStateException("100년 내 미마감 월이 없습니다: " + original);
            }
        }
        return ym;
    }

    static CloseStatusProvider noneClosed() {
        return ym -> false;
    }
}
