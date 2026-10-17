package ga.comm.api.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import ga.comm.outbox.feed.EventFeed;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 이벤트 피드 API (Phase E1, 포털 계약 {@code engine-internal.openapi.yaml} 1.1.0 {@code GET /internal/v1/events}) — {@link EventFeed} 위에 얇게.
 * 200 FeedResponse(필수 {@code tenantId} = 인스턴스 테넌트), 422 {@code AFTER_BEYOND_HEAD}, 400 {@code INVALID_REQUEST}(파라미터 형식·범위).
 * 401은 앱 보안 체인({@code /internal/**} 서비스 토큰)이 낸다. 응답은 이 컨트롤러가 직접 직렬화한 바이트다.
 */
@RestController
@RequestMapping("/internal/v1/events")
public class EventFeedController {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final EventFeed feed;

    public EventFeedController(EventFeed feed) {
        this.feed = feed;
    }

    @GetMapping
    public ResponseEntity<byte[]> events(@RequestParam(name = "afterSeq", required = false) String afterSeq,
                                         @RequestParam(name = "limit", required = false) String limit) {
        long after = parse("afterSeq", afterSeq, true, 0, Long.MAX_VALUE, 0);
        int max = (int) parse("limit", limit, false, 1, EventFeed.MAX_LIMIT, EventFeed.DEFAULT_LIMIT);
        return json(HttpStatus.OK, EventFeed.render(feed.read(after, max)));
    }

    private static long parse(String name, String raw, boolean required, long min, long max, long dflt) {
        if (raw == null) {
            if (required) {
                throw new InvalidRequest(name + " is required");
            }
            return dflt;
        }
        if (!raw.matches("^(0|[1-9][0-9]{0,18})$")) {
            throw new InvalidRequest(name + " must be a non-negative integer");
        }
        long v;
        try {
            v = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new InvalidRequest(name + " is out of range");
        }
        if (v < min || v > max) {
            throw new InvalidRequest(name + " must be between " + min + " and " + max);
        }
        return v;
    }

    static final class InvalidRequest extends RuntimeException {
        InvalidRequest(String message) {
            super(message);
        }
    }

    @ExceptionHandler(InvalidRequest.class)
    ResponseEntity<byte[]> invalid(InvalidRequest e) {
        return json(HttpStatus.BAD_REQUEST, Map.of("code", "INVALID_REQUEST", "message", e.getMessage()));
    }

    @ExceptionHandler(EventFeed.AfterBeyondHeadException.class)
    ResponseEntity<byte[]> beyondHead(EventFeed.AfterBeyondHeadException e) {
        return json(HttpStatus.UNPROCESSABLE_ENTITY, Map.of("code", "AFTER_BEYOND_HEAD", "message", e.getMessage()));
    }

    private static ResponseEntity<byte[]> json(HttpStatus status, Object body) {
        try {
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsBytes(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
