package ga.comm.disclosure.grade.policy;

/**
 * 산출 불가의 <b>원인</b>(엔진이 판정하는 상황, 닫힌 어휘). 응답에 나가는 사유 코드 문자열은 원인이 아니라
 * 등급 정책 데이터({@code unavailableReasons})가 정한다 — 사유 코드를 코드 상수로 두지 않는다(Phase E3 "하지 말 것").
 */
public enum UnavailableCause {
    /** 요청 상품이 측정 기준일에 그 상품군 소속이 아님(또는 외부 키 매핑 없음) */
    NOT_IN_GROUP,
    /** 소속이지만 측정 대상 요율 데이터가 전혀 없음 */
    NO_RATE_DATA,
    /** 요율 데이터는 있으나 측정 기준일에 유효한 것이 없음 */
    OUTSIDE_PERIOD,
    /** 모집단 크기가 정책 minPopulation 미만(또는 측정값 합이 0) — 상품군 전체 산출 불가 */
    INSUFFICIENT_POPULATION,
    /** 임시등록 상품 — v1 측정은 판별 수단이 없어 만들지 않는다(데이터 목록 호환용) */
    TEMP_PRODUCT
}
