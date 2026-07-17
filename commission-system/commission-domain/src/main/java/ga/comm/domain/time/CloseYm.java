package ga.comm.domain.time;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/** 마감월(귀속월) 값객체. "yyyyMM" 6자리 문자열과 상호 변환된다. */
public record CloseYm(YearMonth ym) implements Comparable<CloseYm> {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMM");

    public CloseYm {
        if (ym == null) {
            throw new IllegalArgumentException("마감월은 null일 수 없습니다");
        }
    }

    public static CloseYm of(String yyyymm) {
        if (yyyymm == null || !yyyymm.matches("\\d{6}")) {
            throw new IllegalArgumentException("마감월 형식은 yyyyMM 입니다: " + yyyymm);
        }
        return new CloseYm(YearMonth.parse(yyyymm, FMT));
    }

    public static CloseYm of(int year, int month) {
        return new CloseYm(YearMonth.of(year, month));
    }

    public static CloseYm from(LocalDate date) {
        return new CloseYm(YearMonth.from(date));
    }

    public CloseYm next() {
        return new CloseYm(ym.plusMonths(1));
    }

    public CloseYm prev() {
        return new CloseYm(ym.minusMonths(1));
    }

    public CloseYm plusMonths(long months) {
        return new CloseYm(ym.plusMonths(months));
    }

    public boolean isAfter(CloseYm other) {
        return ym.isAfter(other.ym);
    }

    public boolean isBefore(CloseYm other) {
        return ym.isBefore(other.ym);
    }

    /** DB 저장용 "yyyyMM" 문자열. */
    public String value() {
        return ym.format(FMT);
    }

    @Override
    public int compareTo(CloseYm other) {
        return ym.compareTo(other.ym);
    }

    @Override
    public String toString() {
        return value();
    }
}
