package ga.comm.disclosure.grade.policy;

/** STRICT 순위의 2차 정렬 키(닫힌 어휘). 순서·조합은 순위 정책 데이터가 정한다. */
public enum SecondaryKey {
    /** 측정값 오름차순(1차 기준과 같아 동값 분리에는 기여하지 않는다 — 데이터 예시 호환). */
    MEASURE_ASC,
    /** 계약 productKey 사전순 — 요청 세트 안에서 유일하므로 전순서를 보장한다. */
    PRODUCT_KEY_ASC
}
