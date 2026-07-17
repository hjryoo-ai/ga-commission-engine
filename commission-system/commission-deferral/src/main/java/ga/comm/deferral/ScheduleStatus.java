package ga.comm.deferral;

/** 분급 스케줄 상태. */
public enum ScheduleStatus {
    /** 도래 대기 */
    SCHEDULED,
    /** 조건 충족 — 지급 파이프라인 투입됨 */
    RELEASED,
    /** 도래했으나 조건 미충족(유지 아님 등) — 보류 */
    HELD,
    /** 해약/실효 등으로 소멸 */
    CANCELLED
}
