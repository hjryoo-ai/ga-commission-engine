package ga.comm.disclosure.grade.rank;

import ga.comm.disclosure.grade.policy.RankingPolicySpec;
import ga.comm.disclosure.grade.policy.SecondaryKey;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 요청 세트 내 순위(계약 §4.1): OK 항목만으로, 측정값 오름차순(수수료가 낮을수록 1순위, 규정 정의).
 * SHARED_RANK = 경쟁 순위(1-2-2-4)이고 동값 항목 전부 tie. STRICT = 순위 정책의 2차 키로 분리해 1..m 순열이고,
 * 분리된 항목(원래 동값)도 tie=true로 남긴다. 동점 규칙은 {@link RankingPolicySpec} 데이터가 정한다.
 */
public final class SetRanker {

    private SetRanker() {
    }

    public record Candidate(String productKey, BigDecimal measure) {
        public Candidate {
            Objects.requireNonNull(productKey, "productKey");
            Objects.requireNonNull(measure, "measure");
        }
    }

    public record Ranked(String productKey, BigDecimal measure, int rank, boolean tie) {
    }

    /** 결과는 순위 오름차순, 같은 순위는 productKey 순. */
    public static List<Ranked> rank(List<Candidate> candidates, RankingPolicySpec policy) {
        Set<String> keys = new HashSet<>();
        for (Candidate c : candidates) {
            if (!keys.add(c.productKey())) {
                throw new IllegalArgumentException("duplicate productKey in set: " + c.productKey());
            }
        }
        Comparator<Candidate> byMeasure = Comparator.comparing(Candidate::measure);
        Comparator<Candidate> order = byMeasure;
        for (SecondaryKey key : policy.secondaryKeys()) {
            order = order.thenComparing(switch (key) {
                case MEASURE_ASC -> byMeasure;
                case PRODUCT_KEY_ASC -> Comparator.comparing(Candidate::productKey);
            });
        }
        order = order.thenComparing(Candidate::productKey);   // SHARED_RANK에서 동순위 항목의 표시 순서만 고정
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(order);

        List<Ranked> ranked = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Candidate c = sorted.get(i);
            boolean tie = hasEqualMeasure(sorted, i);
            int rank = switch (policy.tieBreak()) {
                case STRICT -> i + 1;
                case SHARED_RANK -> i > 0 && sorted.get(i - 1).measure().compareTo(c.measure()) == 0 ? ranked.get(i - 1).rank() : i + 1;
            };
            ranked.add(new Ranked(c.productKey(), c.measure(), rank, tie));
        }
        return ranked;
    }

    private static boolean hasEqualMeasure(List<Candidate> sorted, int i) {
        BigDecimal m = sorted.get(i).measure();
        return (i > 0 && sorted.get(i - 1).measure().compareTo(m) == 0)
                || (i + 1 < sorted.size() && sorted.get(i + 1).measure().compareTo(m) == 0);
    }
}
