package ga.comm.domain.type;

/** 계약 라이프사이클 이벤트 유형. 수수료는 전부 이 이벤트에서 파생된다. */
public enum EventType {
    /** 신계약 체결(청약 승인) */
    NEW,
    /** 회차 보험료 입금 */
    PAYMENT,
    /** 해약 */
    CANCEL,
    /** 실효 */
    LAPSE,
    /** 부활 */
    REVIVE,
    /** 감액/보험료 변경 */
    REDUCE,
    /** 청약철회 */
    WITHDRAW,
    /** 보험사 정정 명세 수신 */
    CORRECT
}
