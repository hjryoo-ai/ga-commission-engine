package ga.comm.rule.admin;

import ga.comm.rule.model.IncentiveKey;
import ga.comm.rule.model.IncentiveRule;

import java.util.List;
import java.util.Optional;

/**
 * 시책 마스터 관리 포트 (Phase 13). 요율의 {@code CommRateAdminStore}와 동일 계약이다.
 *
 * <p>{@link #replace}는 승인 워크플로가 만들어낸 변경(DRAFT→ACTIVE, ACTIVE 트리밍, ACTIVE→SUPERSEDED,
 * DRAFT→SUPERSEDED)만 담아야 한다. 구현은 교체 전후 diff({@link IncentiveChangeEntry#diff})를 변경
 * 이력으로 남겨야 한다 — 감사 기록 누락이 구조적으로 불가능하다.
 */
public interface IncentiveAdminStore {

    long nextIncentiveId();

    void insert(IncentiveRule rule);

    void replace(IncentiveRule rule);

    /** 감사 대상 교체 — 전후 diff를 INCENTIVE_CHANGE_HIST에 남긴다. */
    default void replace(IncentiveRule rule, String changedBy) {
        replace(rule);
    }

    List<IncentiveChangeEntry> changeHistory(long incentiveId);

    Optional<IncentiveRule> findById(long incentiveId);

    /** 같은 키(시책 코드)의 전체 버전 이력, 상태 무관, 버전번호 순. */
    List<IncentiveRule> findByKey(IncentiveKey key);
}
