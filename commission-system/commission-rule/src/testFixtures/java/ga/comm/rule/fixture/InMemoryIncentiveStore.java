package ga.comm.rule.fixture;

import ga.comm.rule.IncentiveRepository;
import ga.comm.rule.admin.IncentiveAdminStore;
import ga.comm.rule.admin.IncentiveChangeEntry;
import ga.comm.rule.model.IncentiveKey;
import ga.comm.rule.model.IncentiveRule;
import ga.comm.rule.model.RateStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 인메모리 시책 마스터 — {@link IncentiveAdminStore}(승인)와 {@link IncentiveRepository}(조회)의
 * 레퍼런스 구현. Oracle 어댑터는 계약 테스트(§8.7)로 이 동작과의 동등성을 증명한다.
 *
 * <p>{@code replace}의 변경 이력은 {@link IncentiveChangeEntry#diff}에서 자동 도출한다 — 요율의
 * InMemoryRuleStore와 동일한 감사 파생 규약.
 */
public class InMemoryIncentiveStore implements IncentiveAdminStore, IncentiveRepository {

    private final List<IncentiveRule> rules = new ArrayList<>();
    private final List<IncentiveChangeEntry> changeLog = new ArrayList<>();
    private final AtomicLong idSeq = new AtomicLong(0);

    @Override
    public synchronized long nextIncentiveId() {
        return idSeq.incrementAndGet();
    }

    @Override
    public synchronized void insert(IncentiveRule rule) {
        if (rules.stream().anyMatch(r -> r.incentiveId() == rule.incentiveId())) {
            throw new IllegalStateException("이미 존재하는 incentiveId: " + rule.incentiveId());
        }
        rules.add(rule);
    }

    @Override
    public synchronized void replace(IncentiveRule rule) {
        replace(rule, "system");
    }

    @Override
    public synchronized void replace(IncentiveRule rule, String changedBy) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).incentiveId() == rule.incentiveId()) {
                IncentiveChangeEntry.diff(rules.get(i), rule, changedBy).ifPresent(changeLog::add);
                rules.set(i, rule);
                return;
            }
        }
        throw new IllegalStateException("존재하지 않는 incentiveId: " + rule.incentiveId());
    }

    @Override
    public synchronized List<IncentiveChangeEntry> changeHistory(long incentiveId) {
        return changeLog.stream().filter(e -> e.incentiveId() == incentiveId).toList();
    }

    @Override
    public synchronized Optional<IncentiveRule> findById(long incentiveId) {
        return rules.stream().filter(r -> r.incentiveId() == incentiveId).findFirst();
    }

    @Override
    public synchronized List<IncentiveRule> findByKey(IncentiveKey key) {
        return rules.stream().filter(r -> r.key().equals(key))
                .sorted((a, b) -> Long.compare(a.versionNo(), b.versionNo())).toList();
    }

    @Override
    public synchronized List<IncentiveRule> findActiveAt(LocalDate baseDate) {
        return rules.stream()
                .filter(r -> r.status() == RateStatus.ACTIVE)
                .filter(r -> r.period().contains(baseDate))
                .toList();
    }
}
