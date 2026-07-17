package ga.comm.infra.store;

import ga.comm.infra.mapper.CommRateAdminMapper;
import ga.comm.infra.mapper.RuleQueryMapper;
import ga.comm.rule.admin.CommRateAdminStore;
import ga.comm.rule.admin.RuleChangeEntry;
import ga.comm.rule.model.CommRateRule;
import ga.comm.rule.model.RateKey;
import ga.comm.rule.model.RateStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 요율 승인 워크플로 Oracle 어댑터 (Phase 10b, §6.6).
 *
 * <ul>
 *   <li>replace는 rate_id 행을 잠근 뒤(check) 변경(update)한다 — 같은 행의 동시 교체 직렬화.</li>
 *   <li>교체 전후 diff를 COMM_RATE_CHANGE_HIST에 기록한다(트리밍 감사) — 저장소 계층이
 *       기록하므로 누락이 구조적으로 불가능하다.</li>
 *   <li>"겹치는 ACTIVE 없음"의 최종 심판은 ux_comm_rate_active(V100)다. ACTIVE 전이 UPDATE가
 *       인덱스를 위반하면 스프링 예외 변환으로 DuplicateKeyException이 올라온다 — 호출측
 *       ({@code OracleRateApprovalRunner})이 정상 경합으로 취급해 재시도/거부한다.</li>
 * </ul>
 */
public class OracleCommRateAdminStore implements CommRateAdminStore {

    private final CommRateAdminMapper mapper;

    public OracleCommRateAdminStore(CommRateAdminMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public long nextRateId() {
        return mapper.nextRateId();
    }

    @Override
    public void insert(CommRateRule rule) {
        mapper.insert(toRow(rule));
    }

    @Override
    public void replace(CommRateRule rule) {
        replace(rule, "system");
    }

    @Override
    public void replace(CommRateRule rule, String changedBy) {
        RuleQueryMapper.CommRateRow lockedRow = mapper.lockById(rule.rateId());
        if (lockedRow == null) {
            throw new IllegalStateException("존재하지 않는 rateId: " + rule.rateId());
        }
        CommRateRule before = OracleRuleRepository.toRate(lockedRow);

        mapper.updatePeriodAndStatus(rule.rateId(), rule.period().applyTo(), rule.status().name());
        if (before.status() != RateStatus.ACTIVE && rule.status() == RateStatus.ACTIVE) {
            mapper.recordApproval(rule.rateId(), changedBy);
        }

        RuleChangeEntry.diff(before, rule, changedBy).ifPresent(entry ->
                mapper.insertChangeHist(entry.rateId(), entry.changeType().name(),
                        entry.oldApplyTo(), entry.newApplyTo(),
                        entry.oldStatus().name(), entry.newStatus().name(), entry.changedBy()));
    }

    @Override
    public List<RuleChangeEntry> changeHistory(long rateId) {
        return mapper.changeHistory(rateId).stream()
                .map(row -> new RuleChangeEntry(row.rateId,
                        RuleChangeEntry.ChangeType.valueOf(row.changeType),
                        row.oldApplyTo, row.newApplyTo,
                        RateStatus.valueOf(row.oldStatus), RateStatus.valueOf(row.newStatus),
                        row.changedBy))
                .toList();
    }

    @Override
    public Optional<CommRateRule> findById(long rateId) {
        return Optional.ofNullable(mapper.findById(rateId)).map(OracleRuleRepository::toRate);
    }

    @Override
    public List<CommRateRule> findByKey(RateKey key) {
        return mapper.findByKey(key.direction().name(), key.insurerCd().value(),
                        key.productKey().value(), key.commType().value(), key.installmentNo())
                .stream().map(OracleRuleRepository::toRate).toList();
    }

    private static RuleQueryMapper.CommRateRow toRow(CommRateRule rule) {
        RuleQueryMapper.CommRateRow row = new RuleQueryMapper.CommRateRow();
        row.rateId = rule.rateId();
        row.direction = rule.direction().name();
        row.insurerCd = rule.insurerCd().value();
        row.productKey = rule.productKey().value();
        row.commType = rule.commType().value();
        row.installmentNo = rule.installmentNo();
        row.rate = rule.rate().value();
        row.applyFrom = rule.period().applyFrom();
        row.applyTo = rule.period().applyTo();
        row.versionNo = rule.versionNo();
        row.status = rule.status().name();
        return row;
    }
}
