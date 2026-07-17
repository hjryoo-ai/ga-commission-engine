package ga.comm.rule.admin;

import ga.comm.rule.model.CommRateRule;
import ga.comm.rule.model.RateKey;

import java.util.List;
import java.util.Optional;

/** 요율 관리(등록/승인) 저장 포트. 구현: in-memory(테스트), Oracle(commission-infra). */
public interface CommRateAdminStore {

    long nextRateId();

    void insert(CommRateRule rule);

    /**
     * rateId 기준 교체. 허용되는 변경은 승인 워크플로가 만들어내는 것뿐이다:
     * DRAFT→ACTIVE 전이, ACTIVE 기간 트리밍, ACTIVE→SUPERSEDED 전이.
     */
    void replace(CommRateRule rule);

    /**
     * 변경자 기록이 필요한 교체 (설계서 §6.6 — 트리밍의 변경 감사).
     * 구현은 교체 전후 diff({@link RuleChangeEntry#diff})를 룰 변경 이력으로 남겨야 한다.
     */
    default void replace(CommRateRule rule, String changedBy) {
        replace(rule);
    }

    /** 룰 변경 이력 — 시스템 시간축(§3.1) 감사 조회. 기록 순서대로 반환한다. */
    List<RuleChangeEntry> changeHistory(long rateId);

    Optional<CommRateRule> findById(long rateId);

    /** 상태 무관, 해당 업무 키의 전체 버전 이력. */
    List<CommRateRule> findByKey(RateKey key);
}
