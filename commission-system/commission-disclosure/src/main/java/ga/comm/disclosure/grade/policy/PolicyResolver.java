package ga.comm.disclosure.grade.policy;

import ga.comm.rule.AmbiguousRuleException;
import ga.comm.rule.RuleNotFoundException;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 기준일 단건 해석(엔진 룰 규약 재사용): 2건 이상이면 {@link AmbiguousRuleException}, 0건이면 {@link RuleNotFoundException}.
 * 찾은 행은 {@link PolicyLoader}로 해석·검증한다(결함이면 {@link InvalidPolicyException}).
 */
public final class PolicyResolver implements PolicySource {

    private final DisclosurePolicyRepository repository;

    public PolicyResolver(DisclosurePolicyRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public PolicyVersion<GradingPolicySpec> grading(LocalDate asOf) {
        return resolve(repository.activeGradingPolicies(Objects.requireNonNull(asOf, "asOf")), "DISC_GRADING_POLICY", asOf,
                PolicyLoader::grading);
    }

    @Override
    public PolicyVersion<RankingPolicySpec> ranking(LocalDate asOf) {
        return resolve(repository.activeRankingPolicies(Objects.requireNonNull(asOf, "asOf")), "DISC_RANKING_POLICY", asOf,
                PolicyLoader::ranking);
    }

    private static <S> PolicyVersion<S> resolve(List<PolicyRow> rows, String table, LocalDate asOf, Function<String, S> loader) {
        if (rows.size() > 1) {
            throw new AmbiguousRuleException("유효 버전이 " + rows.size() + "건입니다: " + table + " 기준일=" + asOf + " "
                    + rows.stream().map(PolicyRow::policyVersionId).toList());
        }
        if (rows.isEmpty()) {
            throw new RuleNotFoundException(table + " 기준일=" + asOf + "에 ACTIVE 정책이 없습니다");
        }
        PolicyRow row = rows.getFirst();
        return new PolicyVersion<>(row.policyVersionId(), row.applyFrom(), row.applyTo(), loader.apply(row.body()));
    }
}
