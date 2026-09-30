package ga.comm.disclosure.grade.policy;

import java.math.BigDecimal;

/**
 * 등급 1개 = 평균 대비 비율 구간. {@code min == null}이면 하한 없음, {@code max == null}이면 상한 없음.
 * 코드·라벨·ordinal·경계는 전부 등급 정책 데이터에서 온다.
 */
public record GradeBand(String code, String label, int ordinal, RatioBound min, RatioBound max) {

    public boolean contains(BigDecimal ratio) {
        boolean aboveMin = min == null
                || (min.inclusive() ? ratio.compareTo(min.value()) >= 0 : ratio.compareTo(min.value()) > 0);
        boolean belowMax = max == null
                || (max.inclusive() ? ratio.compareTo(max.value()) <= 0 : ratio.compareTo(max.value()) < 0);
        return aboveMin && belowMax;
    }
}
