package ga.comm.api.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import ga.comm.settlement.simulation.DryRunSimulator;
import ga.comm.settlement.simulation.SimulationModel;
import ga.comm.settlement.simulation.SimulationRejected;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 시뮬레이션 API(E2 ②, 포털 계약 {@code POST /internal/v1/simulations/new-contract}·{@code /policy-event}) — {@link DryRunSimulator} 위에 얇게.
 * 요청은 계약 스키마의 형식 검사(400 {@code INVALID_REQUEST}), 거부는 {@link SimulationRejected}의 코드·상태 그대로(404 {@code AGENT_NOT_FOUND}·
 * {@code POLICY_NOT_FOUND}, 422 {@code INSURER_AMBIGUOUS}·{@code UNSUPPORTED_EVENT_TYPE}). 401은 앱 보안 체인({@code /internal/**} 서비스 토큰).
 * 응답은 계약 필드 순서로 직접 직렬화한 바이트 — 선택 필드는 값이 없으면 싣지 않는다. 저장 0.
 */
@RestController
@RequestMapping("/internal/v1/simulations")
public class SimulationInternalController {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    /** 포털 계약 defs {@code agentId}·{@code id}·{@code date}와 같은 형식. */
    private static final Pattern AGENT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern ID = Pattern.compile("^\\S(.*\\S)?$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final List<String> EVENT_TYPES = List.of("CANCEL", "LAPSE", "WITHDRAW", "REDUCE", "REINSTATE", "PAYMENT_OVERDUE");

    private final DryRunSimulator simulator;

    public SimulationInternalController(DryRunSimulator simulator) {
        this.simulator = simulator;
    }

    @PostMapping(path = "/new-contract", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> newContract(@RequestBody byte[] body) {
        JsonNode n = object(body);
        List<String> codes = null;
        if (n.has("incentiveCodes")) {
            JsonNode arr = n.get("incentiveCodes");
            if (!arr.isArray()) {
                throw new EventFeedController.InvalidRequest("incentiveCodes must be an array");
            }
            codes = new ArrayList<>();
            for (JsonNode c : arr) {
                if (!c.isTextual() || c.asText().isEmpty()) {
                    throw new EventFeedController.InvalidRequest("incentiveCodes items must be non-empty strings");
                }
                codes.add(c.asText());
            }
        }
        SimulationModel.NewContractResult r = simulator.simulateNewContract(new SimulationModel.NewContractRequest(
                text(n, "agentId", AGENT, true), text(n, "productKey", ID, true), nonNegative(n, "monthlyPremium"),
                date(n, "contractDate", true), positiveInt(n, "paymentTermMonths"), text(n, "insurerCd", ID, false), codes));
        return json(HttpStatus.OK, newContractBody(r));
    }

    @PostMapping(path = "/policy-event", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> policyEvent(@RequestBody byte[] body) {
        JsonNode n = object(body);
        String eventType = text(n, "eventType", ID, true);
        if (!EVENT_TYPES.contains(eventType)) {
            throw new EventFeedController.InvalidRequest("eventType must be one of " + EVENT_TYPES);
        }
        SimulationModel.PolicyEventResult r = simulator.simulatePolicyEvent(new SimulationModel.PolicyEventRequest(
                text(n, "agentId", AGENT, true), text(n, "policyNo", ID, true), eventType, date(n, "asOfDate", true),
                positiveInt(n, "elapsedInstallments"), n.has("newMonthlyPremium") ? nonNegative(n, "newMonthlyPremium") : null));
        return json(HttpStatus.OK, policyEventBody(r));
    }

    /** 계약 {@code NewContractResult}의 필드 순서. */
    static Map<String, Object> newContractBody(SimulationModel.NewContractResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (r.limitAmount() != null) {
            m.put("limitAmount", r.limitAmount());
        }
        List<Map<String, Object>> fy = new ArrayList<>();
        for (SimulationModel.Installment i : r.fyCommissionByInstallment()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("no", i.no());
            e.put("amount", i.amount());
            fy.add(e);
        }
        m.put("fyCommissionByInstallment", fy);
        List<Map<String, Object>> inc = new ArrayList<>();
        for (SimulationModel.Incentive i : r.incentives()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("code", i.code());
            e.put("amount", i.amount());
            e.put("limitCutAmt", i.limitCutAmt());
            e.put("limitIncluded", i.limitIncluded());
            inc.add(e);
        }
        m.put("incentives", inc);
        if (r.projectedAccum() != null) {
            m.put("projectedAccum", r.projectedAccum());
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("firstYearPayout", r.deferral().firstYearPayout());
        List<Map<String, Object>> schedule = new ArrayList<>();
        for (SimulationModel.DueSlice s : r.deferral().schedule()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("dueYm", s.dueYm().toString());
            e.put("amount", s.amount());
            schedule.add(e);
        }
        d.put("schedule", schedule);
        m.put("deferral", d);
        m.put("ruleSnapshotId", r.ruleSnapshotId());
        m.put("generatedAt", r.generatedAt());
        return m;
    }

    /** 계약 {@code PolicyEventResult}의 필드 순서. */
    static Map<String, Object> policyEventBody(SimulationModel.PolicyEventResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clawbackAmount", r.clawbackAmount());
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("closeYm", r.offsetAgainstMonth().closeYm().toString());
        o.put("before", r.offsetAgainstMonth().before());
        o.put("after", r.offsetAgainstMonth().after());
        m.put("offsetAgainstMonth", o);
        m.put("receivableRemaining", r.receivableRemaining());
        List<Map<String, Object>> cancelled = new ArrayList<>();
        for (SimulationModel.CancelledDeferral c : r.cancelledDeferrals()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("scheduleId", c.scheduleId());
            e.put("dueYm", c.dueYm().toString());
            e.put("amount", c.amount());
            cancelled.add(e);
        }
        m.put("cancelledDeferrals", cancelled);
        m.put("ruleSnapshotId", r.ruleSnapshotId());
        return m;
    }

    @ExceptionHandler(EventFeedController.InvalidRequest.class)
    ResponseEntity<byte[]> invalid(EventFeedController.InvalidRequest e) {
        return json(HttpStatus.BAD_REQUEST, Map.of("code", "INVALID_REQUEST", "message", e.getMessage()));
    }

    @ExceptionHandler(SimulationRejected.class)
    ResponseEntity<byte[]> rejected(SimulationRejected e) {
        return json(HttpStatus.valueOf(e.kind().status()), Map.of("code", e.kind().name(), "message", e.getMessage()));
    }

    private static JsonNode object(byte[] body) {
        try {
            JsonNode n = JSON.readTree(body);
            if (n == null || !n.isObject()) {
                throw new EventFeedController.InvalidRequest("body must be a JSON object");
            }
            return n;
        } catch (java.io.IOException e) {
            throw new EventFeedController.InvalidRequest("body is not valid JSON");
        }
    }

    private static String text(JsonNode n, String field, Pattern format, boolean required) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            if (required) {
                throw new EventFeedController.InvalidRequest(field + " is required");
            }
            return null;
        }
        if (!v.isTextual() || !format.matcher(v.asText()).matches() || v.asText().length() > 64) {
            throw new EventFeedController.InvalidRequest(field + " has an invalid format");
        }
        return v.asText();
    }

    private static long nonNegative(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong() || v.asLong() < 0) {
            throw new EventFeedController.InvalidRequest(field + " must be a non-negative integer");
        }
        return v.asLong();
    }

    private static Integer positiveInt(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isIntegralNumber() || !v.canConvertToInt() || v.asInt() < 1) {
            throw new EventFeedController.InvalidRequest(field + " must be an integer >= 1");
        }
        return v.asInt();
    }

    private static LocalDate date(JsonNode n, String field, boolean required) {
        String s = text(n, field, DATE, required);
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new EventFeedController.InvalidRequest(field + " is not a valid date");
        }
    }

    private static ResponseEntity<byte[]> json(HttpStatus status, Object body) {
        try {
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsBytes(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
