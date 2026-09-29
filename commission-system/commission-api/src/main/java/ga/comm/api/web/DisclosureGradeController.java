package ga.comm.api.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ga.comm.disclosure.grade.DisclosureGradeService;
import ga.comm.disclosure.grade.GradeRequest;
import ga.comm.disclosure.grade.GradeRequestException;
import ga.comm.disclosure.grade.PolicySelfCheckException;
import ga.comm.disclosure.grade.TenantMismatchException;
import ga.comm.disclosure.grade.policy.InvalidPolicyException;
import ga.comm.disclosure.grade.snapshot.SnapshotIntegrityException;
import ga.comm.disclosure.grade.snapshot.SnapshotNotFoundException;
import ga.comm.rule.AmbiguousRuleException;
import ga.comm.rule.RuleNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 비교설명 등급·순위 API (Phase E3, ga-disclosure 계약 {@code engine-disclosure.openapi.yaml} 1.1.0) — 서비스 위에 얇게.
 *
 * <p><b>응답 본문은 저장된 정규 문자열의 바이트 그대로</b>다(POST·GET 모두). Jackson으로 다시 직렬화하지 않으므로 재조회가
 * 발급 응답과 바이트 단위로 같고 ratioToAvg가 재포맷되지 않는다. 인증({@code /internal/**} 서비스 토큰)은 앱의 보안 필터가 한다.
 *
 * <p>오류는 계약 Problem {@code {code, message}}로, 이 컨트롤러 전용 매핑이다(전역 {@link ApiExceptionHandler}의
 * Ambiguous=422 매핑은 기존 API용으로 그대로 둔다): 400 요청 오류 · 403 TENANT_MISMATCH · 404 SNAPSHOT_NOT_FOUND ·
 * 409 AMBIGUOUS_POLICY · 422 NO_POLICY / POLICY_SELF_CHECK_FAILED / INVALID_POLICY · 500 SNAPSHOT_INTEGRITY.
 */
@RestController
@RequestMapping("/internal/v1/disclosure/commission-grades")
public class DisclosureGradeController {

    private final DisclosureGradeService service;

    public DisclosureGradeController(DisclosureGradeService service) {
        this.service = service;
    }

    /** 계약 CommissionGradesRequest. 계약에 additionalProperties 제한이 없으므로 모르는 필드는 무시한다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RequestBodyDto(String tenantId, String asOfDate, String productGroupCode, List<ProductDto> products) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductDto(String productKey, String insurerCode) {
    }

    /** 계약 Problem. */
    public record Problem(String code, String message) {
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> compute(@RequestBody RequestBodyDto body) {
        GradeRequest request = GradeRequest.of(body.tenantId(), body.asOfDate() == null ? null : LocalDate.parse(body.asOfDate()),
                body.productGroupCode(), body.products() == null ? null
                        : body.products().stream().map(p -> p == null ? null : new GradeRequest.Product(p.productKey(), p.insurerCode()))
                        .toList());
        return canonical(service.issue(request).responseCanonical());
    }

    @GetMapping("/{snapshotId}")
    public ResponseEntity<byte[]> get(@PathVariable String snapshotId) {
        return canonical(service.refetch(snapshotId));
    }

    private static ResponseEntity<byte[]> canonical(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body.getBytes(StandardCharsets.UTF_8));
    }

    @ExceptionHandler(GradeRequestException.class)
    ResponseEntity<Problem> badRequest(GradeRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, e.code(), e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, DateTimeParseException.class})
    ResponseEntity<Problem> unreadable(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "request body is not a valid CommissionGradesRequest");
    }

    @ExceptionHandler(TenantMismatchException.class)
    ResponseEntity<Problem> tenant(TenantMismatchException e) {
        return problem(HttpStatus.FORBIDDEN, "TENANT_MISMATCH", e.getMessage());
    }

    @ExceptionHandler(SnapshotNotFoundException.class)
    ResponseEntity<Problem> notFound(SnapshotNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "SNAPSHOT_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(AmbiguousRuleException.class)
    ResponseEntity<Problem> ambiguous(AmbiguousRuleException e) {
        return problem(HttpStatus.CONFLICT, "AMBIGUOUS_POLICY", e.getMessage());
    }

    @ExceptionHandler(RuleNotFoundException.class)
    ResponseEntity<Problem> noPolicy(RuleNotFoundException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "NO_POLICY", e.getMessage());
    }

    @ExceptionHandler(PolicySelfCheckException.class)
    ResponseEntity<Problem> selfCheck(PolicySelfCheckException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "POLICY_SELF_CHECK_FAILED", e.getMessage());
    }

    @ExceptionHandler(InvalidPolicyException.class)
    ResponseEntity<Problem> invalidPolicy(InvalidPolicyException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_POLICY", e.getMessage());
    }

    @ExceptionHandler(SnapshotIntegrityException.class)
    ResponseEntity<Problem> integrity(SnapshotIntegrityException e) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "SNAPSHOT_INTEGRITY", e.getMessage());
    }

    private static ResponseEntity<Problem> problem(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new Problem(code, message));
    }
}
