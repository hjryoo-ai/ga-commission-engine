package ga.comm.disclosure.grade;

import ga.comm.disclosure.grade.policy.AsOfRule;
import ga.comm.disclosure.grade.policy.PeriodKind;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 측정 기간: 계약 basis.period 라벨과 측정 기준일.
 * TRAILING_QUARTER + REQUEST_DATE: 기준일이 속한 분기의 <b>직전</b> 분기(완료 분기). 예) 2026-09-23 → 2026Q2, 2026-06-30.
 * 분기 말일 당일(예: 09-30)도 그 분기는 아직 끝나지 않은 것으로 본다.
 */
public record MeasurePeriod(String label, LocalDate measureDate) {

    public static MeasurePeriod resolve(PeriodKind kind, AsOfRule rule, LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        LocalDate reference = switch (rule) {
            case REQUEST_DATE -> asOf;
        };
        return switch (kind) {
            case TRAILING_QUARTER -> {
                int quarter = (reference.getMonthValue() - 1) / 3 + 1;
                LocalDate quarterStart = LocalDate.of(reference.getYear(), (quarter - 1) * 3 + 1, 1);
                LocalDate previousEnd = quarterStart.minusDays(1);
                int previousQuarter = (previousEnd.getMonthValue() - 1) / 3 + 1;
                yield new MeasurePeriod(previousEnd.getYear() + "Q" + previousQuarter, previousEnd);
            }
        };
    }
}
