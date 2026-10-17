package ga.comm.api.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import ga.comm.domain.time.CloseYm;
import ga.comm.outbox.feed.StatementQuery;
import ga.comm.settlement.estimate.MonthClosedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 명세 조회 API(E2 ③, 포털 계약 {@code GET /internal/v1/agents/{agentId}/statements/{ym}}) — {@link StatementQuery} 위에 얇게.
 * 200(ESTIMATE = StatementEstimated 모양, CONFIRMED = StatementConfirmed 모양), 400 {@code INVALID_REQUEST}, 404 {@code AGENT_NOT_FOUND}·
 * {@code STATEMENT_NOT_CONFIRMED}, 422 {@code MONTH_CLOSED}. 401은 앱 보안 체인({@code /internal/**} 서비스 토큰). 응답은 직접 직렬화한 바이트.
 */
@RestController
@RequestMapping("/internal/v1/agents/{agentId}/statements/{ym}")
public class StatementQueryController {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    /** 포털 계약 defs {@code agentId}·{@code ym}과 같은 형식. */
    private static final Pattern AGENT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern YM = Pattern.compile("^[0-9]{4}(0[1-9]|1[0-2])$");

    private final StatementQuery query;

    public StatementQueryController(StatementQuery query) {
        this.query = query;
    }

    @GetMapping
    public ResponseEntity<byte[]> statement(@PathVariable("agentId") String agentId, @PathVariable("ym") String ym,
                                            @RequestParam(name = "mode", required = false) String mode) {
        if (!AGENT.matcher(agentId).matches()) {
            throw new EventFeedController.InvalidRequest("agentId has an invalid format");
        }
        if (!YM.matcher(ym).matches()) {
            throw new EventFeedController.InvalidRequest("ym must be yyyyMM");
        }
        CloseYm month = CloseYm.of(ym);
        return switch (mode == null ? "" : mode) {
            case "ESTIMATE" -> json(HttpStatus.OK, query.estimate(agentId, month));
            case "CONFIRMED" -> json(HttpStatus.OK, query.confirmed(agentId, month));
            default -> throw new EventFeedController.InvalidRequest("mode must be ESTIMATE or CONFIRMED");
        };
    }

    @ExceptionHandler(EventFeedController.InvalidRequest.class)
    ResponseEntity<byte[]> invalid(EventFeedController.InvalidRequest e) {
        return json(HttpStatus.BAD_REQUEST, Map.of("code", "INVALID_REQUEST", "message", e.getMessage()));
    }

    @ExceptionHandler(StatementQuery.AgentNotFound.class)
    ResponseEntity<byte[]> agentNotFound(StatementQuery.AgentNotFound e) {
        return json(HttpStatus.NOT_FOUND, Map.of("code", "AGENT_NOT_FOUND", "message", e.getMessage()));
    }

    @ExceptionHandler(StatementQuery.NotConfirmed.class)
    ResponseEntity<byte[]> notConfirmed(StatementQuery.NotConfirmed e) {
        return json(HttpStatus.NOT_FOUND, Map.of("code", "STATEMENT_NOT_CONFIRMED", "message", e.getMessage()));
    }

    @ExceptionHandler(MonthClosedException.class)
    ResponseEntity<byte[]> monthClosed(MonthClosedException e) {
        return json(HttpStatus.UNPROCESSABLE_ENTITY, Map.of("code", "MONTH_CLOSED", "message", e.getMessage()));
    }

    private static ResponseEntity<byte[]> json(HttpStatus status, Object body) {
        try {
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsBytes(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
