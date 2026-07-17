package ga.comm.api.disclosure;

import ga.comm.domain.money.Money;

/**
 * 비교설명 등급 산정 정책 (설계서 §1, Phase 16). 등급 기준(분위/절대 임계/상대)은 아직 외부 확정
 * 사안이므로(§11 #13) {@link RankingService}에서 <b>분리</b>해 이 인터페이스로 갈아끼운다 — 기준이
 * 바뀌어도 순위 산출 로직은 그대로다. 기본 구현은 {@link TercileGradingPolicy}(3분위).
 *
 * @param rank  1부터 시작하는 내림차순 순위
 * @param total 전체 대상 수
 * @param value 해당 대상의 순액 (절대 임계 정책이 쓸 수 있도록 함께 넘긴다)
 */
@FunctionalInterface
public interface GradingPolicy {
    String grade(int rank, int total, Money value);
}
