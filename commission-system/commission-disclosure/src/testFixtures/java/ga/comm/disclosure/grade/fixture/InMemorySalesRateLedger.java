package ga.comm.disclosure.grade.fixture;

import ga.comm.disclosure.grade.measure.SalesRateLedger;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.rule.AmbiguousRuleException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class InMemorySalesRateLedger implements SalesRateLedger {

    private record Rate(String insurer, String product, String commType, Integer installmentNo, BigDecimal rate,
                        LocalDate from, LocalDate to) {
    }

    private final List<Rate> rates = new ArrayList<>();

    /** FY_COMM·회차 NULL·기간 [from, 9999-12-31] 요율. */
    public InMemorySalesRateLedger fy(String insurer, String product, String rate, LocalDate from) {
        return add(insurer, product, "FY_COMM", null, rate, from, InMemoryDisclosurePolicyRepository.MAX_DATE);
    }

    public InMemorySalesRateLedger add(String insurer, String product, String commType, Integer installmentNo, String rate,
                                       LocalDate from, LocalDate to) {
        rates.add(new Rate(insurer, product, commType, installmentNo, new BigDecimal(rate), from, to));
        return this;
    }

    @Override
    public Optional<BigDecimal> inboundRateAt(InsurerCode insurer, ProductKey product, CommTypeCode commType,
                                              Integer installmentNo, LocalDate date) {
        List<Rate> hits = matching(insurer, product, commType, installmentNo).stream()
                .filter(r -> !r.from.isAfter(date) && !r.to.isBefore(date)).toList();
        if (hits.size() > 1) {
            throw new AmbiguousRuleException("유효 버전이 " + hits.size() + "건입니다: COMM_RATE " + product);
        }
        return hits.stream().findFirst().map(Rate::rate);
    }

    @Override
    public boolean hasAnyInboundRate(InsurerCode insurer, ProductKey product, CommTypeCode commType, Integer installmentNo) {
        return !matching(insurer, product, commType, installmentNo).isEmpty();
    }

    private List<Rate> matching(InsurerCode insurer, ProductKey product, CommTypeCode commType, Integer installmentNo) {
        return rates.stream().filter(r -> r.insurer.equals(insurer.value()) && r.product.equals(product.value())
                && r.commType.equals(commType.value()) && Objects.equals(r.installmentNo, installmentNo)).toList();
    }
}
