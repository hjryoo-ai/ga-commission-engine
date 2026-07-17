package ga.comm.limit;

import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.rule.model.LimitRule;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 한도 원장 저장 포트.
 * DB 구현은 find/getOrCreate 시 행 잠금(SELECT FOR UPDATE)을 걸어 지급 트랜잭션과
 * 원장 갱신을 동일 트랜잭션에서 직렬화한다 (설계서 §6.1.6).
 */
public interface LimitLedgerStore {

    Optional<LimitLedger> find(PolicyNo policyNo, AgentId agentId);

    /** 없으면 개설: fy_start=계약일, fy_end=계약일+윈도우−1일, 한도=월납보험료×배수. */
    LimitLedger getOrCreate(PolicyNo policyNo, AgentId agentId, LocalDate contractDate,
                            Money monthlyPremium, LimitRule rule);

    /** 전기/재산정 결과 반영. 인메모리 구현은 no-op일 수 있다. */
    void save(LimitLedger ledger);

    /** 마감 배치 전수 검증용. */
    List<LimitLedger> findAll();
}
