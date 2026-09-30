package ga.comm.disclosure.grade.policy;

import java.time.LocalDate;
import java.util.List;

/**
 * 등급·순위 정책 버전 조회 포트. 기준일 필수(부록 B-3) — "현재 유효" API는 없다.
 * 기준일에 ACTIVE이고 {@code apply_from ≤ asOf ≤ apply_to}인 행을 <b>전부</b> 돌려주고, 유일성 판정은
 * {@link PolicyResolver}가 한다(2건 이상 → Ambiguous, 0건 → RuleNotFound).
 */
public interface DisclosurePolicyRepository {

    List<PolicyRow> activeGradingPolicies(LocalDate asOf);

    List<PolicyRow> activeRankingPolicies(LocalDate asOf);
}
