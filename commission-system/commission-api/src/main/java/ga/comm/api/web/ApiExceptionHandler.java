package ga.comm.api.web;

import ga.comm.rule.AmbiguousRuleException;
import ga.comm.rule.RuleNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 오류 매핑 표준 (설계서 §10 Phase 12).
 *
 * <p>fail-fast 도메인 예외를 4xx 계열 문제 응답으로 매핑하되 <b>내부 상세(SQL·스택)는 비노출</b>한다.
 * 규약 위반은 명시적 코드로 구분한다: 룰 데이터 미비(422), 요청 오류·실승인자 누락(400),
 * 상태 위반(마감월 귀속 차단·이미 마감·중복 승인 거부, 409). 예상 밖 예외는 500 + 일반 메시지
 * (도메인 예외 메시지는 업무 문구라 노출하지만, 500은 원문을 숨긴다).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ProblemResponse(int status, String code, String message) {
    }

    /** 필수 룰 데이터 누락(fail-fast, §6.1.4). */
    @ExceptionHandler(RuleNotFoundException.class)
    public ResponseEntity<ProblemResponse> ruleNotFound(RuleNotFoundException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "RULE_NOT_FOUND", e.getMessage());
    }

    /** 겹치는 유효 룰(§6.6) — 데이터 완결성 위반. */
    @ExceptionHandler(AmbiguousRuleException.class)
    public ResponseEntity<ProblemResponse> ambiguous(AmbiguousRuleException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "AMBIGUOUS_RULE", e.getMessage());
    }

    /** 잘못된 요청(식별자 형식 오류, 실승인자 누락 등). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemResponse> badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    /** 상태 위반(마감월 귀속 차단·이미 마감·DRAFT 아님 등). */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ProblemResponse> conflict(IllegalStateException e) {
        return problem(HttpStatus.CONFLICT, "STATE_CONFLICT", e.getMessage());
    }

    /** 예상 밖 오류 — 내부 상세를 숨긴다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemResponse> internal(Exception e) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "내부 오류가 발생했습니다");
    }

    private static ResponseEntity<ProblemResponse> problem(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ProblemResponse(status.value(), code, message));
    }
}
