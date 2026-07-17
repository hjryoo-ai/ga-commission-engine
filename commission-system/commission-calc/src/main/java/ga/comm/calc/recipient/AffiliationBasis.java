package ga.comm.calc.recipient;

/**
 * 수급자 결정의 소속·등급 기준일 (미결정 §11.6 — 사규 파라미터).
 * 어느 답이 와도 코드 수정 없이 반영되도록 설정값으로 관리한다.
 */
public enum AffiliationBasis {
    /** 지급(이벤트 발생) 시점 소속 기준 */
    EVENT_DATE,
    /** 계약 체결 시점 소속 기준 */
    CONTRACT_DATE
}
