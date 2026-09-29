package ga.comm.disclosure.grade.measure;

import com.fasterxml.jackson.databind.JsonNode;
import ga.comm.disclosure.grade.group.GroupMember;
import ga.comm.disclosure.grade.policy.UnavailableCause;
import ga.comm.domain.id.CommTypeCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * v1 측정 함수 — <b>예시 측정</b>: 상품의 매출측(INBOUND) 요율 중 정책 {@code measureParams}의
 * {@code commType}·{@code installmentNo}와 일치하는 것을 측정 기준일로 읽는다(예: 초년도 판매수수료 FY_COMM, 회차 NULL).
 * 판매수수료율의 정확한 정의(모수·기간)는 외부 확정 사실(설계서 §11 #13)이므로 확정되면 이 함수를 교체한다.
 *
 * <p>측정 기준일에 요율이 있으면 값, 이 키의 요율이 있으나 그날 유효하지 않으면 OUTSIDE_PERIOD, 아예 없으면 NO_RATE_DATA.
 */
public final class FySalesCommissionRateMeasure implements Measure {

    public static final String KEY = "FY_SALES_COMMISSION_RATE";

    private final SalesRateLedger ledger;

    public FySalesCommissionRateMeasure(SalesRateLedger ledger) {
        this.ledger = Objects.requireNonNull(ledger);
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String groupAvgSource() {
        return "ENGINE_LEDGER";
    }

    @Override
    public List<String> validateParams(JsonNode params) {
        List<String> problems = new ArrayList<>();
        if (params == null || !params.isObject()) {
            return List.of("measureParams: required object");
        }
        params.properties().forEach(e -> {
            String k = e.getKey();
            if (!k.equals("commType") && !k.equals("installmentNo")) {
                problems.add("measureParams." + k + ": unknown key");
            }
        });
        JsonNode commType = params.get("commType");
        if (commType == null || !commType.isTextual() || commType.textValue().isBlank()) {
            problems.add("measureParams.commType: required string");
        }
        JsonNode installment = params.get("installmentNo");
        if (installment == null) {
            problems.add("measureParams.installmentNo: required (integer or null — no default)");
        } else if (!installment.isNull() && !(installment.isIntegralNumber() && installment.canConvertToInt()
                && installment.intValue() >= 1)) {
            problems.add("measureParams.installmentNo: integer ≥ 1 or null");
        }
        return problems;
    }

    @Override
    public MeasureOutcome measure(GroupMember member, LocalDate measureDate, JsonNode params) {
        CommTypeCode commType = new CommTypeCode(params.get("commType").textValue());
        JsonNode installment = params.get("installmentNo");
        Integer installmentNo = installment.isNull() ? null : installment.intValue();
        Optional<BigDecimal> rate = ledger.inboundRateAt(member.insurerCd(), member.productKey(), commType, installmentNo,
                measureDate);
        if (rate.isPresent()) {
            return new MeasureOutcome.Value(rate.get());
        }
        return new MeasureOutcome.Missing(ledger.hasAnyInboundRate(member.insurerCd(), member.productKey(), commType,
                installmentNo) ? UnavailableCause.OUTSIDE_PERIOD : UnavailableCause.NO_RATE_DATA);
    }
}
