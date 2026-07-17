package ga.comm.rule.model;

/** 1200% 한도 초과분 처리 정책 (미결정 §11.3 — 데이터로 관리). */
public enum OverLimitAction {
    /** 초과분 삭감(소멸) */
    CUT,
    /** 초년도 종료 후 이연 지급 */
    DEFER_AFTER_FY
}
