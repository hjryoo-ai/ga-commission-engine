package ga.comm.rule.model;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Rate;
import ga.comm.domain.type.Direction;

import java.util.Objects;

/**
 * 수수료 요율 레코드 (COMM_RATE 1행).
 *
 * @param installmentNo 회차. null = 일시 지급형(신계약 성립 등 회차와 무관한 지급)
 */
public record CommRateRule(
        long rateId,
        Direction direction,
        InsurerCode insurerCd,
        ProductKey productKey,
        CommTypeCode commType,
        Integer installmentNo,
        Rate rate,
        EffectivePeriod period,
        long versionNo,
        RateStatus status
) {
    public CommRateRule {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(insurerCd, "insurerCd");
        Objects.requireNonNull(productKey, "productKey");
        Objects.requireNonNull(commType, "commType");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(status, "status");
    }

    public RateKey key() {
        return new RateKey(direction, insurerCd, productKey, commType, installmentNo);
    }

    public CommRateRule withStatus(RateStatus newStatus) {
        return new CommRateRule(rateId, direction, insurerCd, productKey, commType,
                installmentNo, rate, period, versionNo, newStatus);
    }

    public CommRateRule withPeriod(EffectivePeriod newPeriod) {
        return new CommRateRule(rateId, direction, insurerCd, productKey, commType,
                installmentNo, rate, newPeriod, versionNo, status);
    }
}
