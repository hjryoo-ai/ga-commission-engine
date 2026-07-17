package ga.comm.clawback;

/** 환수 채권 상태. */
public enum ReceivableStatus {
    /** 미상계 잔액 있음 */
    OPEN,
    /** 일부 상계됨 */
    OFFSET,
    /** 전액 상계 완료 */
    CLOSED,
    /** 대손 처리 (해촉자 회수 불능 등 — 별도 회수 프로세스) */
    WRITTEN_OFF
}
