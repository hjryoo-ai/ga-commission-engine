package ga.comm.domain.type;

/**
 * 수수료 계산 결과 상태.
 *
 * <p>허용 전이: CALCULATED → CONFIRMED → PAID, 그리고 (CALCULATED|CONFIRMED|PAID) → REVERSED.
 * 상태 전이 외의 UPDATE는 금지된다(불변 원장).
 */
public enum CalcStatus {
    CALCULATED,
    CONFIRMED,
    PAID,
    REVERSED;

    public boolean canTransitionTo(CalcStatus next) {
        return switch (this) {
            case CALCULATED -> next == CONFIRMED || next == REVERSED;
            case CONFIRMED -> next == PAID || next == REVERSED;
            case PAID -> next == REVERSED;
            case REVERSED -> false;
        };
    }
}
