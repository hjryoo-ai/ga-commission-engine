package ga.comm.rule.model;

import java.time.LocalDate;

/**
 * 업무 유효기간 [applyFrom, applyTo] — 양끝 포함.
 * 열린 기간은 applyTo = 9999-12-31 로 표현한다 (DB 규약과 동일).
 */
public record EffectivePeriod(LocalDate applyFrom, LocalDate applyTo) {

    public static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);

    public EffectivePeriod {
        if (applyFrom == null || applyTo == null) {
            throw new IllegalArgumentException("유효기간은 null일 수 없습니다");
        }
        if (applyFrom.isAfter(applyTo)) {
            throw new IllegalArgumentException(
                    "유효기간 시작일이 종료일보다 늦습니다: " + applyFrom + " ~ " + applyTo);
        }
    }

    /** applyFrom부터 무기한 유효. */
    public static EffectivePeriod from(LocalDate applyFrom) {
        return new EffectivePeriod(applyFrom, MAX_DATE);
    }

    public static EffectivePeriod of(LocalDate applyFrom, LocalDate applyTo) {
        return new EffectivePeriod(applyFrom, applyTo);
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(applyFrom) && !date.isAfter(applyTo);
    }

    public boolean overlaps(EffectivePeriod other) {
        return !applyFrom.isAfter(other.applyTo) && !other.applyFrom.isAfter(applyTo);
    }

    /** 신규 버전 개시일 전날로 종료일을 당긴다 (버전 교체 시 트리밍). */
    public EffectivePeriod truncatedBefore(LocalDate newApplyFrom) {
        return new EffectivePeriod(applyFrom, newApplyFrom.minusDays(1));
    }

    @Override
    public String toString() {
        return applyFrom + "~" + (applyTo.equals(MAX_DATE) ? "" : applyTo);
    }
}
