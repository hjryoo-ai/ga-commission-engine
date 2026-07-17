package ga.comm.calc.net;

import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.RecipientType;

import java.util.Collection;
import java.util.Objects;

/**
 * 순액 산출 공용 컴포넌트 (설계서 §3.0 합산 규약, 부록 B-4) — 순액은 반드시 이것으로만 구한다.
 *
 * <p><b>전 상태 합산</b>: REVERSED 원본도 합산에 남고 음수 reversal이 상쇄한다. 상태 필터
 * 합산은 마감 전 정정(원본 REVERSED·reversal·rebook이 같은 월에 공존)에서 원본만 빠지고
 * reversal은 남아 <b>이중 차감</b>을 낳는다 — Phase 11에서 실증된 결함이며, 이 컴포넌트가
 * 존재하는 이유다. 개별 서비스·쿼리의 직접 SUM 작성은 금지된다.
 */
public final class NetAmountCalculator {

    private final CommCalcStore calcStore;

    public NetAmountCalculator(CommCalcStore calcStore) {
        this.calcStore = Objects.requireNonNull(calcStore);
    }

    /** 레코드 집합의 순액 — 상태 필터 없이 전부 합산한다 (지급 런의 grossNet 등). */
    public static Money netOf(Collection<CommCalcRecord> records) {
        return records.stream().map(CommCalcRecord::calcAmount).reduce(Money.ZERO, Money::plus);
    }

    /** 수급자×마감월 순액 (유형 전체) — 전 상태 합산. */
    public Money netOf(RecipientType type, String recipientId, CloseYm closeYm) {
        return netOf(calcStore.findByCloseYm(closeYm).stream()
                .filter(r -> r.recipientType() == type && r.recipientId().equals(recipientId))
                .toList());
    }

    /** 수급자×유형×마감월 순액 — 전 상태 합산. */
    public Money netOf(RecipientType type, String recipientId, CommTypeCode commType, CloseYm closeYm) {
        return netOf(calcStore.findByCloseYm(closeYm).stream()
                .filter(r -> r.recipientType() == type && r.recipientId().equals(recipientId)
                        && r.commType().equals(commType))
                .toList());
    }
}
