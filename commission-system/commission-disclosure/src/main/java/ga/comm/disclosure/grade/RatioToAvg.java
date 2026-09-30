package ga.comm.disclosure.grade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 평균 대비 비율 — <b>이 클래스가 비교설명 경로의 유일한 반올림 지점</b>이다(중앙 반올림, 부록 B-1의 연장).
 *
 * <p>ratio = measure × n / Σmeasure 를 {@code BigDecimal.divide(Σ, scale, mode)} 한 번으로 계산한다 — 평균을 따로 반올림하지
 * 않으므로 이중 반올림이 없다. 표시 문자열 {@link #text()}는 같은 값의 {@code toPlainString()}이고 여기서 <b>한 번만</b> 만들어진다.
 * 이후 어떤 경로도 이 문자열을 숫자로 되돌려 다시 포맷하지 않는다(응답·스냅샷·재조회가 같은 문자열을 나른다).
 * 등급은 이 반올림된 값으로 판정한다(인쇄된 비율과 등급이 어긋나지 않게 — TODO(confirm#13)).
 */
public record RatioToAvg(BigDecimal value, String text) {

    public RatioToAvg {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(text, "text");
    }

    public static RatioToAvg of(BigDecimal measure, int population, BigDecimal sum, int scale, RoundingMode mode) {
        if (population < 1 || sum.signum() <= 0) {
            throw new IllegalArgumentException("ratio needs a positive population sum");
        }
        BigDecimal ratio = measure.multiply(BigDecimal.valueOf(population)).divide(sum, scale, mode);
        return new RatioToAvg(ratio, ratio.toPlainString());
    }
}
