package ga.comm.deferral;

/**
 * 분급 스케줄 상태 전이의 사유 — 닫힌 어휘(Phase E1 D-10, 계획 §2). 전이를 일으키는 지점이 정한다:
 * 도래 지급({@link #DUE_RELEASED}), 유지 조건 미충족 보류({@link #POLICY_NOT_INFORCE}), 해약·실효·철회로 잔여 소멸
 * ({@link #POLICY_TERMINATED}), 원본 계산의 취소분개로 소멸({@link #SOURCE_REVERSED}). 포털 이벤트 {@code DeferralStatusChanged.reason}이 된다.
 */
public enum DeferralTransitionReason {
    DUE_RELEASED,
    POLICY_NOT_INFORCE,
    POLICY_TERMINATED,
    SOURCE_REVERSED
}
