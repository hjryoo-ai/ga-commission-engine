package ga.comm.disclosure.grade;

import ga.comm.disclosure.grade.fixture.DisclosurePolicyRepositoryContract;
import ga.comm.disclosure.grade.fixture.InMemoryDisclosurePolicyRepository;
import ga.comm.disclosure.grade.policy.DisclosurePolicyRepository;

import java.time.LocalDate;

/** E3 계약 스위트 — 인메모리 레퍼런스. Oracle 어댑터는 commission-infra integrationTest가 같은 스위트를 돈다. */
class InMemoryDisclosurePolicyRepositoryTest extends DisclosurePolicyRepositoryContract {

    private final InMemoryDisclosurePolicyRepository repository = new InMemoryDisclosurePolicyRepository();

    @Override
    protected DisclosurePolicyRepository repository() {
        return repository;
    }

    @Override
    protected void clear() {
        repository.clear();
    }

    @Override
    protected void insertGrading(String id, LocalDate from, LocalDate to, String status, String body) {
        repository.addGrading(id, from, to, status, body);
    }

    @Override
    protected void insertRanking(String id, LocalDate from, LocalDate to, String status, String body) {
        repository.addRanking(id, from, to, status, body);
    }
}
