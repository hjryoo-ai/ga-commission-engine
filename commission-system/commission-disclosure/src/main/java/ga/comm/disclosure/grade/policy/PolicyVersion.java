package ga.comm.disclosure.grade.policy;

import java.time.LocalDate;

/** 기준일로 해석된 정책 버전 1건(유효기간은 엔진 규약대로 양끝 포함, apply_to 기본 9999-12-31). */
public record PolicyVersion<S>(String id, LocalDate applyFrom, LocalDate applyTo, S spec) {
}
