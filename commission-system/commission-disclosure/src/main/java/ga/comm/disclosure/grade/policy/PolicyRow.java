package ga.comm.disclosure.grade.policy;

import java.time.LocalDate;

/** 정책 버전 테이블의 원시 행(body는 JSON 원문). 해석·검증은 {@link PolicyLoader}. */
public record PolicyRow(String policyVersionId, LocalDate applyFrom, LocalDate applyTo, String status, String body) {
}
