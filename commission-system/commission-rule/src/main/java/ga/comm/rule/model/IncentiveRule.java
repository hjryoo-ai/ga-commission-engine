package ga.comm.rule.model;

import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;

import java.util.Objects;

/**
 * 시책 마스터 1행 (INCENTIVE_MST, Phase 13). 요율 마스터(CommRateRule)와 동일한 유효기간+버전+승인
 * 패턴을 따른다 — 트리밍/SUPERSEDE, diff 기반 변경 감사(§6.6), 겹침 불변식(같은 incentiveCd).
 *
 * <p>대상 필터(insurerCd/productKey/channel)는 <b>null = 전체(와일드카드)</b>. 조건식은 SpEL이며
 * 등록·승인 시점에 샌드박스에서 파싱·시평가로 검증된다(fail-fast). 산식은 {@link PayoutKind}로 최종
 * 시책 금액을 산출한다.
 */
public record IncentiveRule(
        long incentiveId,
        String incentiveCd,
        InsurerCode insurerCd,     // null = 전체 보험사
        ProductKey productKey,     // null = 전체 상품
        String channel,            // null = 전체 채널
        String conditionExpr,
        PayoutKind payoutKind,
        Money fixedAmount,         // FIXED 전용 (그 외 null)
        Rate premiumRate,          // PREMIUM_RATE 전용 (그 외 null)
        EffectivePeriod period,
        long versionNo,
        RateStatus status
) {

    public IncentiveRule {
        Objects.requireNonNull(incentiveCd, "incentiveCd");
        Objects.requireNonNull(conditionExpr, "conditionExpr");
        Objects.requireNonNull(payoutKind, "payoutKind");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(status, "status");
        if (incentiveCd.isBlank()) {
            throw new IllegalArgumentException("시책 코드가 비어 있습니다");
        }
        if (conditionExpr.isBlank()) {
            throw new IllegalArgumentException("조건식이 비어 있습니다: " + incentiveCd);
        }
        switch (payoutKind) {
            case FIXED -> {
                Objects.requireNonNull(fixedAmount, "FIXED 시책은 fixedAmount 필수");
                if (premiumRate != null) {
                    throw new IllegalArgumentException("FIXED 시책은 premiumRate를 갖지 않는다");
                }
                if (fixedAmount.isNegative()) {
                    throw new IllegalArgumentException("시책 고정 금액은 음수일 수 없다: " + fixedAmount);
                }
            }
            case PREMIUM_RATE -> {
                Objects.requireNonNull(premiumRate, "PREMIUM_RATE 시책은 premiumRate 필수");
                if (fixedAmount != null) {
                    throw new IllegalArgumentException("PREMIUM_RATE 시책은 fixedAmount를 갖지 않는다");
                }
            }
        }
    }

    public IncentiveKey key() {
        return new IncentiveKey(incentiveCd);
    }

    public IncentiveRule withStatus(RateStatus newStatus) {
        return new IncentiveRule(incentiveId, incentiveCd, insurerCd, productKey, channel,
                conditionExpr, payoutKind, fixedAmount, premiumRate, period, versionNo, newStatus);
    }

    public IncentiveRule withPeriod(EffectivePeriod newPeriod) {
        return new IncentiveRule(incentiveId, incentiveCd, insurerCd, productKey, channel,
                conditionExpr, payoutKind, fixedAmount, premiumRate, newPeriod, versionNo, status);
    }

    /** 대상 필터 매칭 — null 필터는 와일드카드. 채널은 대소문자 무시. */
    public boolean matchesTarget(InsurerCode eventInsurer, ProductKey eventProduct, String eventChannel) {
        if (insurerCd != null && !insurerCd.equals(eventInsurer)) {
            return false;
        }
        if (productKey != null && !productKey.equals(eventProduct)) {
            return false;
        }
        return channel == null || channel.equalsIgnoreCase(eventChannel);
    }
}
