package ga.comm.api.disclosure;

import ga.comm.domain.money.Money;

/**
 * 3분위 등급 정책 (기본 구현) — 상위 1/3 A, 중위 B, 하위 C. 순위 위치만 쓰고 값은 보지 않는다.
 * 올림 기준으로 소수 케이스(N&lt;3)도 안정적으로 배분한다.
 */
public final class TercileGradingPolicy implements GradingPolicy {

    @Override
    public String grade(int rank, int total, Money value) {
        int third = Math.max(1, (int) Math.ceil(total / 3.0));
        if (rank <= third) {
            return "A";
        }
        return rank <= 2 * third ? "B" : "C";
    }
}
