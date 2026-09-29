package ga.comm.disclosure.grade.policy;

import java.util.List;

/** 순위 정책 body({@code DISC_RANKING_POLICY.body}). STRICT면 2차 키가 전순서를 보장해야 한다(로드 시 검증). */
public record RankingPolicySpec(TieBreak tieBreak, List<SecondaryKey> secondaryKeys) {

    public RankingPolicySpec {
        secondaryKeys = List.copyOf(secondaryKeys);
    }
}
