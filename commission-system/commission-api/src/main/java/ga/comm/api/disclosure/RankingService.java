package ga.comm.api.disclosure;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 대형 GA 비교설명 데이터 (설계서 §1 — 500인 이상 의무, Phase 15). 상품×보험사별 수수료 순액으로
 * 순위·등급을 <b>산출</b>한다. 산출 로직만 담고 <b>표시 서식은 분리</b>한다 — 결과 {@link CommissionRank}는
 * 순수 데이터이며, 공시와 마찬가지로 표시 서식은 확정 시 {@link DisclosureFormat}류로 갈아끼운다.
 *
 * <p>순액은 {@link DisclosureAggregate}(추출 계층, NetAmountCalculator 경유)에서 오므로 여기서 다시
 * SUM하지 않는다 — figure 순액을 (보험사×상품) 단위로 롤업만 한다.
 */
public class RankingService {

    /** 순위·등급 한 건 (순수 데이터 — 표시 서식과 무관). */
    public record CommissionRank(InsurerCode insurerCd, ProductKey productKey, Money net,
                                 int rank, String grade) {
    }

    /**
     * 기준 유형({@code basis}, 예: FY_COMM 판매수수료)의 (보험사×상품) 순액으로 내림차순 순위를 매기고
     * 3분위 등급(A/B/C)을 부여한다. 동액은 안정 정렬(입력 순서)로 순위를 유지한다.
     */
    public List<CommissionRank> rank(DisclosureAggregate aggregate, CommTypeCode basis) {
        Map<Key, Money> byKey = new LinkedHashMap<>();
        for (DisclosureAggregate.Figure f : aggregate.figures()) {
            if (f.commType().equals(basis)) {
                byKey.merge(new Key(f.insurerCd(), f.productKey()), f.net(), Money::plus);
            }
        }

        List<Map.Entry<Key, Money>> sorted = new ArrayList<>(byKey.entrySet());
        sorted.sort(Comparator.comparingLong((Map.Entry<Key, Money> e) -> e.getValue().toLong())
                .reversed());

        int total = sorted.size();
        List<CommissionRank> ranks = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            int rank = i + 1;
            ranks.add(new CommissionRank(sorted.get(i).getKey().insurerCd,
                    sorted.get(i).getKey().productKey, sorted.get(i).getValue(),
                    rank, gradeFor(rank, total)));
        }
        return ranks;
    }

    /** 3분위 등급 — 상위 1/3 A, 중위 B, 하위 C (올림 기준으로 소수 케이스도 안정). */
    private static String gradeFor(int rank, int total) {
        int third = Math.max(1, (int) Math.ceil(total / 3.0));
        if (rank <= third) {
            return "A";
        }
        return rank <= 2 * third ? "B" : "C";
    }

    private record Key(InsurerCode insurerCd, ProductKey productKey) {
    }
}
