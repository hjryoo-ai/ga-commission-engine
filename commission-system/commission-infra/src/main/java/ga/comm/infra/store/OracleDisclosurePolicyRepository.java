package ga.comm.infra.store;

import ga.comm.disclosure.grade.policy.DisclosurePolicyRepository;
import ga.comm.disclosure.grade.policy.PolicyRow;
import ga.comm.infra.mapper.DisclosureGradeMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** 등급·순위 정책 버전 Oracle 어댑터(Phase E3). 유일성 판정은 PolicyResolver(Ambiguous fail-fast). */
public class OracleDisclosurePolicyRepository implements DisclosurePolicyRepository {

    private final DisclosureGradeMapper mapper;

    public OracleDisclosurePolicyRepository(DisclosureGradeMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public List<PolicyRow> activeGradingPolicies(LocalDate asOf) {
        return mapper.activeGradingPolicies(Objects.requireNonNull(asOf)).stream().map(OracleDisclosurePolicyRepository::row).toList();
    }

    @Override
    public List<PolicyRow> activeRankingPolicies(LocalDate asOf) {
        return mapper.activeRankingPolicies(Objects.requireNonNull(asOf)).stream().map(OracleDisclosurePolicyRepository::row).toList();
    }

    private static PolicyRow row(DisclosureGradeMapper.PolicyRowData r) {
        return new PolicyRow(r.policyVersionId, r.applyFrom, r.applyTo, r.status, r.body);
    }
}
