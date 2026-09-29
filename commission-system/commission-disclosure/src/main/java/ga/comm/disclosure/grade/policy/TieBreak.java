package ga.comm.disclosure.grade.policy;

/**
 * 세트 내 동점 처리(계약 {@code tieBreak}). 코드가 분기하는 닫힌 어휘 — 어느 값을 쓸지는 순위 정책 데이터가 정한다.
 * SHARED_RANK: 경쟁 순위(1-2-2-4), 동값 전부 tie. STRICT: 2차 키로 분리해 1..m 순열, 분리된 항목은 tie(원래 동값이었음).
 */
public enum TieBreak {
    SHARED_RANK,
    STRICT
}
