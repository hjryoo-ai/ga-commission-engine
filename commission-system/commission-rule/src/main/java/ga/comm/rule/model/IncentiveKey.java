package ga.comm.rule.model;

import java.util.Objects;

/**
 * 시책 버전 키 (Phase 13) — 겹침 불변식의 단위.
 *
 * <p>요율 키(요율은 키당 정확히 1건 해석)와 달리, 시책은 서로 다른 코드끼리 <b>중첩 적용</b>된다.
 * 따라서 겹침 불변식은 <b>같은 {@code incentiveCd}</b>에 대해서만 성립한다 — 한 시책의 버전 타임라인에
 * 겹치는 ACTIVE가 없어야 한다(승인 시 트리밍/SUPERSEDE). 대상 필터(보험사/상품/채널)는 키가 아니라
 * 시책의 속성이며, 다른 코드의 시책은 공존·중첩된다.
 */
public record IncentiveKey(String incentiveCd) {

    public IncentiveKey {
        Objects.requireNonNull(incentiveCd, "incentiveCd");
        if (incentiveCd.isBlank()) {
            throw new IllegalArgumentException("시책 코드가 비어 있습니다");
        }
    }
}
