package ga.comm.rule.model;

import ga.comm.domain.money.Rate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 분급 커브 (DEFERRAL_CURVE + DTL). 커브 자체가 데이터 — 4년 커브를 7년 커브로
 * 바꾸는 것은 레코드 교체만으로 가능해야 한다 (설계서 §6.2 완성 기준).
 *
 * @param points 경과월별 지급 비율. monthNo 0 = 계약 당월 즉시 지급분. 비율 합은 정확히 1.
 */
public record DeferralCurve(
        long curveId,
        String curveName,
        EffectivePeriod period,
        List<CurvePoint> points
) {
    public record CurvePoint(int monthNo, Rate pct) {
        public CurvePoint {
            if (monthNo < 0) {
                throw new IllegalArgumentException("경과월은 0 이상이어야 합니다: " + monthNo);
            }
            Objects.requireNonNull(pct, "pct");
        }
    }

    public DeferralCurve {
        Objects.requireNonNull(curveName, "curveName");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(points, "points");
        if (points.isEmpty()) {
            throw new IllegalArgumentException("커브 포인트가 비어 있습니다");
        }
        points = List.copyOf(points);
        long distinctMonths = points.stream().map(CurvePoint::monthNo).distinct().count();
        if (distinctMonths != points.size()) {
            throw new IllegalArgumentException("경과월이 중복된 커브 포인트가 있습니다");
        }
        BigDecimal sum = points.stream()
                .map(p -> p.pct().value())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("커브 비율 합은 정확히 1이어야 합니다: " + sum);
        }
    }
}
