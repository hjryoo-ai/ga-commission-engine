package ga.comm.disclosure.grade;

import ga.comm.disclosure.grade.policy.TieBreak;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 응답 생성 후 자기 검증(Phase E3 작업 2). 단조성은 구성상 성립하지만(등급·순위가 같은 측정값에서 나온다) 정책 데이터 모순을
 * 잡기 위해 응답 자체를 다시 본다 — ga-disclosure가 소비 측에서 하는 검사(§6.3 ii·iii)와 같은 조건:
 * <ol>
 *   <li>rankInSet 오름차순에서 gradeOrdinal 비감소, 같은 순위끼리 gradeOrdinal 동일</li>
 *   <li>STRICT: OK 항목 m개의 rankInSet이 1..m 순열 / SHARED_RANK: 경쟁 순위(r₁=1, rₖ ∈ {rₖ₋₁, k})이고 동순위 항목은 전부 tie</li>
 * </ol>
 * 실패하면 {@link PolicySelfCheckException} — 스냅샷을 만들지 않는다.
 */
public final class SelfCheck {

    private SelfCheck() {
    }

    public static void verify(List<GradeResult> results, TieBreak tieBreak) {
        List<GradeResult.Ok> ok = results.stream()
                .filter(GradeResult.Ok.class::isInstance).map(GradeResult.Ok.class::cast)
                .sorted(Comparator.comparingInt(GradeResult.Ok::rankInSet)).toList();
        List<String> violations = new ArrayList<>();
        for (int i = 1; i < ok.size(); i++) {
            GradeResult.Ok prev = ok.get(i - 1);
            GradeResult.Ok cur = ok.get(i);
            if (cur.gradeOrdinal() < prev.gradeOrdinal()) {
                violations.add("rank " + cur.rankInSet() + " (" + cur.productKey() + ") has gradeOrdinal " + cur.gradeOrdinal()
                        + " < " + prev.gradeOrdinal() + " of rank " + prev.rankInSet());
            }
            if (cur.rankInSet() == prev.rankInSet() && cur.gradeOrdinal() != prev.gradeOrdinal()) {
                violations.add("shared rank " + cur.rankInSet() + " has different gradeOrdinals");
            }
        }
        switch (tieBreak) {
            case STRICT -> {
                for (int i = 0; i < ok.size(); i++) {
                    if (ok.get(i).rankInSet() != i + 1) {
                        violations.add("STRICT ranks are not the permutation 1.." + ok.size());
                        break;
                    }
                }
            }
            case SHARED_RANK -> {
                for (int i = 0; i < ok.size(); i++) {
                    int r = ok.get(i).rankInSet();
                    boolean valid = i == 0 ? r == 1 : (r == ok.get(i - 1).rankInSet() || r == i + 1);
                    if (!valid) {
                        violations.add("SHARED_RANK ranks are not a competition ranking at position " + (i + 1));
                        break;
                    }
                }
                Map<Integer, Long> perRank = ok.stream().collect(Collectors.groupingBy(GradeResult.Ok::rankInSet,
                        Collectors.counting()));
                ok.stream().filter(r -> perRank.get(r.rankInSet()) > 1 && !r.tie())
                        .forEach(r -> violations.add("shared rank " + r.rankInSet() + " item " + r.productKey() + " is not tie"));
            }
        }
        if (!violations.isEmpty()) {
            throw new PolicySelfCheckException(violations);
        }
    }
}
