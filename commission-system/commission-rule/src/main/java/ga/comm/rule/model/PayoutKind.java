package ga.comm.rule.model;

/**
 * 시책 산식 종류 (Phase 13, 1차 범위).
 *
 * <ul>
 *   <li>{@link #FIXED} — 고정 금액(원). {@code fixedAmount} 사용.
 *   <li>{@link #PREMIUM_RATE} — 월납보험료 × 요율. {@code premiumRate} 사용.
 * </ul>
 *
 * <p>산식은 최종 시책 금액을 직접 산출한다(반올림은 COMM_TYPE_MST(INCENTIVE) 정책). 실적 연동
 * 산식 등 확장은 후속 Phase에서 새 종류로 추가한다 — 기존 종류는 수정하지 않는다(부록 B-12).
 */
public enum PayoutKind {
    FIXED,
    PREMIUM_RATE
}
