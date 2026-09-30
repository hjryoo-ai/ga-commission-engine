package ga.comm.disclosure.grade.measure;

import ga.comm.disclosure.grade.policy.UnavailableCause;

import java.math.BigDecimal;
import java.util.Objects;

/** 측정 결과: 값이 있거나, 없는 원인이 있거나. */
public sealed interface MeasureOutcome {

    record Value(BigDecimal value) implements MeasureOutcome {
        public Value {
            Objects.requireNonNull(value, "value");
            if (value.signum() < 0) {
                throw new IllegalArgumentException("measure must not be negative: " + value.toPlainString());
            }
        }
    }

    record Missing(UnavailableCause cause) implements MeasureOutcome {
        public Missing {
            Objects.requireNonNull(cause, "cause");
        }
    }
}
