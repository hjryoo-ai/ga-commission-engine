package ga.comm.disclosure.grade.fixture;

import ga.comm.disclosure.grade.policy.DisclosurePolicyRepository;
import ga.comm.disclosure.grade.policy.PolicyRow;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 인메모리 레퍼런스 — Oracle 어댑터와 같은 계약 스위트({@link DisclosurePolicyRepositoryContract})를 통과한다. */
public final class InMemoryDisclosurePolicyRepository implements DisclosurePolicyRepository {

    public static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);

    private final List<PolicyRow> grading = new ArrayList<>();
    private final List<PolicyRow> ranking = new ArrayList<>();

    public InMemoryDisclosurePolicyRepository addGrading(String id, LocalDate from, LocalDate to, String status, String body) {
        grading.add(new PolicyRow(id, from, to == null ? MAX_DATE : to, status, body));
        return this;
    }

    public InMemoryDisclosurePolicyRepository addRanking(String id, LocalDate from, LocalDate to, String status, String body) {
        ranking.add(new PolicyRow(id, from, to == null ? MAX_DATE : to, status, body));
        return this;
    }

    public void clear() {
        grading.clear();
        ranking.clear();
    }

    @Override
    public List<PolicyRow> activeGradingPolicies(LocalDate asOf) {
        return active(grading, asOf);
    }

    @Override
    public List<PolicyRow> activeRankingPolicies(LocalDate asOf) {
        return active(ranking, asOf);
    }

    private static List<PolicyRow> active(List<PolicyRow> rows, LocalDate asOf) {
        return rows.stream().filter(r -> "ACTIVE".equals(r.status()))
                .filter(r -> !r.applyFrom().isAfter(asOf) && !r.applyTo().isBefore(asOf)).toList();
    }
}
