package ga.comm.disclosure.grade;

import ga.comm.disclosure.grade.policy.AsOfRule;
import ga.comm.disclosure.grade.policy.PeriodKind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class MeasurePeriodTest {

    @ParameterizedTest
    @CsvSource({
            "2026-09-23, 2026Q2, 2026-06-30",
            "2026-09-30, 2026Q2, 2026-06-30",
            "2026-10-01, 2026Q3, 2026-09-30",
            "2026-01-01, 2025Q4, 2025-12-31",
            "2026-03-31, 2025Q4, 2025-12-31",
            "2026-04-01, 2026Q1, 2026-03-31",
    })
    void 직전_완료_분기(LocalDate asOf, String label, LocalDate measureDate) {
        MeasurePeriod p = MeasurePeriod.resolve(PeriodKind.TRAILING_QUARTER, AsOfRule.REQUEST_DATE, asOf);
        assertThat(p.label()).isEqualTo(label);
        assertThat(p.measureDate()).isEqualTo(measureDate);
    }
}
