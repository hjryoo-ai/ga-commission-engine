package ga.comm.api.disclosure;

import ga.comm.disclosure.grade.policy.RankingPolicySpec;
import ga.comm.disclosure.grade.rank.SetRanker;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 대형 GA 비교설명 데이터 (설계서 §1 — 500인 이상 의무, Phase 15). 상품×보험사별 수수료 순액으로
 * 순위·등급을 <b>산출</b>한다. 산출 로직만 담고 <b>표시 서식은 분리</b>한다 — 결과 {@link CommissionRank}는
 * 순수 데이터이며, 공시와 마찬가지로 표시 서식은 확정 시 {@link DisclosureFormat}류로 갈아끼운다.
 *
 * <p>순액은 {@link DisclosureAggregate}(추출 계층, NetAmountCalculator 경유)에서 오므로 여기서 다시
 * SUM하지 않는다 — figure 순액을 (보험사×상품) 단위로 롤업만 한다. <b>등급 산정은 {@link GradingPolicy}로
 * 분리</b>했다(기준 미확정 §11 #13) — 순위 산출 로직은 정책과 무관하다.
 */
public class RankingService {

    private final GradingPolicy gradingPolicy;

    /** 기본 3분위 등급. */
    public RankingService() {
        this(new TercileGradingPolicy());
    }

    public RankingService(GradingPolicy gradingPolicy) {
        this.gradingPolicy = Objects.requireNonNull(gradingPolicy);
    }

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
            Money net = sorted.get(i).getValue();
            ranks.add(new CommissionRank(sorted.get(i).getKey().insurerCd,
                    sorted.get(i).getKey().productKey, net, rank,
                    gradingPolicy.grade(rank, total, net)));
        }
        return ranks;
    }

    /**
     * 비교<b>설명</b> 세트 내 순위(Phase E3, ga-disclosure 계약 §4.1) — 위 {@link #rank}(비교공시 집계, 순액 내림차순)와 별개다.
     * 요청 세트의 OK 항목만, 측정값 오름차순(수수료가 낮을수록 1순위). 동점 규칙은 순위 정책 데이터({@link RankingPolicySpec})가
     * 정한다: SHARED_RANK = 경쟁 순위, STRICT = 2차 키로 1..m. 구현은 {@link SetRanker}.
     */
    public List<SetRanker.Ranked> rankInSet(List<SetRanker.Candidate> candidates, RankingPolicySpec policy) {
        return SetRanker.rank(candidates, policy);
    }

    private record Key(InsurerCode insurerCd, ProductKey productKey) {
    }
}
