package ga.comm.disclosure.grade.measure;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 매출측(INBOUND = 보험사→GA) 요율 원장 조회 포트({@code COMM_RATE}, status=ACTIVE). 기준일 필수.
 * 기준일에 유효한 버전이 2건 이상이면 구현이 {@code AmbiguousRuleException}(fail-fast).
 */
public interface SalesRateLedger {

    Optional<BigDecimal> inboundRateAt(InsurerCode insurer, ProductKey product, CommTypeCode commType, Integer installmentNo,
                                       LocalDate date);

    /** 기간과 무관하게 이 키의 ACTIVE 요율이 한 건이라도 있는가(OUTSIDE_PERIOD vs NO_RATE_DATA 구분). */
    boolean hasAnyInboundRate(InsurerCode insurer, ProductKey product, CommTypeCode commType, Integer installmentNo);
}
