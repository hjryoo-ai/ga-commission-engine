package ga.comm.infra.store;

import ga.comm.disclosure.grade.measure.SalesRateLedger;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.type.Direction;
import ga.comm.infra.mapper.DisclosureGradeMapper;
import ga.comm.rule.RuleRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * 매출측 요율 원장 어댑터(Phase E3): 기준일 요율은 기존 {@link RuleRepository#findRate}(INBOUND, Ambiguous fail-fast)를
 * 그대로 쓰고, 기간 무관 존재 여부만 새 조회로 확인한다.
 */
public class OracleSalesRateLedger implements SalesRateLedger {

    private final RuleRepository rules;
    private final DisclosureGradeMapper mapper;

    public OracleSalesRateLedger(RuleRepository rules, DisclosureGradeMapper mapper) {
        this.rules = Objects.requireNonNull(rules);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public Optional<BigDecimal> inboundRateAt(InsurerCode insurer, ProductKey product, CommTypeCode commType, Integer installmentNo,
                                              LocalDate date) {
        return rules.findRate(Direction.INBOUND, insurer, product, commType, installmentNo, date).map(r -> r.rate().value());
    }

    @Override
    public boolean hasAnyInboundRate(InsurerCode insurer, ProductKey product, CommTypeCode commType, Integer installmentNo) {
        return mapper.countInboundRates(insurer.value(), product.value(), commType.value(), installmentNo) > 0;
    }
}
