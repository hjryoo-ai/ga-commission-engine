package ga.comm.disclosure.grade.policy;

import java.math.BigDecimal;
import java.util.Objects;

/** 등급 구간의 한쪽 경계. 값과 포함 여부 둘 다 데이터다(포함 여부 기본값 없음 — 부록 B-13). */
public record RatioBound(BigDecimal value, boolean inclusive) {

    public RatioBound {
        Objects.requireNonNull(value, "value");
    }
}
