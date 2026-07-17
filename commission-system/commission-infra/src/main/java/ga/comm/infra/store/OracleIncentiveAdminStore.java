package ga.comm.infra.store;

import ga.comm.infra.mapper.IncentiveAdminMapper;
import ga.comm.rule.admin.IncentiveAdminStore;
import ga.comm.rule.admin.IncentiveApprovalConflictException;
import ga.comm.rule.admin.IncentiveChangeEntry;
import ga.comm.rule.model.IncentiveKey;
import ga.comm.rule.model.IncentiveRule;
import ga.comm.rule.model.RateStatus;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 시책 승인 워크플로 Oracle 어댑터 (Phase 13). 요율의 {@code OracleCommRateAdminStore}와 동일 —
 * replace는 incentive_id 행을 잠근 뒤 변경하고, 교체 전후 diff를 INCENTIVE_CHANGE_HIST에 기록한다
 * (저장소 계층이 기록하므로 감사 누락이 구조적으로 불가능).
 */
public class OracleIncentiveAdminStore implements IncentiveAdminStore {

    private final IncentiveAdminMapper mapper;

    public OracleIncentiveAdminStore(IncentiveAdminMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public long nextIncentiveId() {
        return mapper.nextIncentiveId();
    }

    @Override
    public void insert(IncentiveRule rule) {
        mapper.insert(toRow(rule));
    }

    @Override
    public void replace(IncentiveRule rule) {
        replace(rule, "system");
    }

    @Override
    public void replace(IncentiveRule rule, String changedBy) {
        IncentiveAdminMapper.IncentiveRow lockedRow = mapper.lockById(rule.incentiveId());
        if (lockedRow == null) {
            throw new IllegalStateException("존재하지 않는 incentiveId: " + rule.incentiveId());
        }
        IncentiveRule before = OracleIncentiveRepository.toRule(lockedRow);

        try {
            mapper.updatePeriodAndStatus(rule.incentiveId(), rule.period().applyTo(),
                    rule.status().name());
        } catch (DuplicateKeyException conflict) {
            // ux_incentive_active 위반 = 같은 코드·개시일의 ACTIVE 시책이 이미 존재(동시 승인 경합).
            // 요율은 재시도 러너가 이 시점의 스프링 예외를 잡아 재판정하지만, 시책 러너는 후속 Phase다 —
            // 지금은 명시적 409(경합 거부)로 번역만 한다(raw 500 누출 차단, §6.6).
            throw new IncentiveApprovalConflictException(
                    "시책 승인 경합: 같은 코드·개시일의 ACTIVE 시책이 이미 존재합니다 (incentiveId="
                            + rule.incentiveId() + ", cd=" + before.incentiveCd() + ")", conflict);
        }
        if (before.status() != RateStatus.ACTIVE && rule.status() == RateStatus.ACTIVE) {
            mapper.recordApproval(rule.incentiveId(), changedBy);
        }

        IncentiveChangeEntry.diff(before, rule, changedBy).ifPresent(entry ->
                mapper.insertChangeHist(entry.incentiveId(), entry.changeType().name(),
                        entry.oldApplyTo(), entry.newApplyTo(),
                        entry.oldStatus().name(), entry.newStatus().name(), entry.changedBy()));
    }

    @Override
    public List<IncentiveChangeEntry> changeHistory(long incentiveId) {
        return mapper.changeHistory(incentiveId).stream()
                .map(row -> new IncentiveChangeEntry(row.incentiveId,
                        IncentiveChangeEntry.ChangeType.valueOf(row.changeType),
                        row.oldApplyTo, row.newApplyTo,
                        RateStatus.valueOf(row.oldStatus), RateStatus.valueOf(row.newStatus),
                        row.changedBy))
                .toList();
    }

    @Override
    public Optional<IncentiveRule> findById(long incentiveId) {
        return Optional.ofNullable(mapper.findById(incentiveId))
                .map(OracleIncentiveRepository::toRule);
    }

    @Override
    public List<IncentiveRule> findByKey(IncentiveKey key) {
        return mapper.findByKey(key.incentiveCd()).stream()
                .map(OracleIncentiveRepository::toRule).toList();
    }

    private static IncentiveAdminMapper.IncentiveRow toRow(IncentiveRule rule) {
        IncentiveAdminMapper.IncentiveRow row = new IncentiveAdminMapper.IncentiveRow();
        row.incentiveId = rule.incentiveId();
        row.incentiveCd = rule.incentiveCd();
        row.insurerCd = rule.insurerCd() == null ? null : rule.insurerCd().value();
        row.productKey = rule.productKey() == null ? null : rule.productKey().value();
        row.channel = rule.channel();
        row.conditionExpr = rule.conditionExpr();
        row.payoutKind = rule.payoutKind().name();
        row.fixedAmount = rule.fixedAmount() == null ? null : rule.fixedAmount().toLong();
        row.premiumRate = rule.premiumRate() == null ? null : rule.premiumRate().value();
        row.applyFrom = rule.period().applyFrom();
        row.applyTo = rule.period().applyTo();
        row.versionNo = rule.versionNo();
        row.status = rule.status().name();
        return row;
    }
}
