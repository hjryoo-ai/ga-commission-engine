package ga.comm.infra.store;

import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;
import ga.comm.domain.type.RecipientType;
import ga.comm.infra.mapper.CommCalcMapper;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * COMM_CALC Oracle 어댑터 — 불변 원장 (부록 B-2).
 * insert와 상태 전이만 제공하며, 전이는 행 잠금 후 check-then-update로 직렬화한다.
 */
public class OracleCommCalcStore implements CommCalcStore {

    private final CommCalcMapper mapper;

    public OracleCommCalcStore(CommCalcMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public CommCalcRecord insert(CommCalcRecord record) {
        if (record.calcId() != null) {
            throw new IllegalArgumentException("이미 calcId가 부여된 레코드입니다: " + record.calcId());
        }
        CommCalcMapper.Row row = toRow(record);
        mapper.insert(row);
        return record.withCalcId(row.calcId);
    }

    @Override
    public Optional<CommCalcRecord> findById(long calcId) {
        return Optional.ofNullable(mapper.findById(calcId)).map(OracleCommCalcStore::toDomain);
    }

    @Override
    public List<CommCalcRecord> findByEventId(long eventId) {
        return mapper.findByEventId(eventId).stream().map(OracleCommCalcStore::toDomain).toList();
    }

    @Override
    public List<CommCalcRecord> findByPolicyAndRecipient(PolicyNo policyNo, String recipientId) {
        return mapper.findByPolicyAndRecipient(policyNo.value(), recipientId).stream()
                .map(OracleCommCalcStore::toDomain).toList();
    }

    @Override
    public List<CommCalcRecord> findByCloseYm(CloseYm closeYm) {
        return mapper.findByCloseYm(closeYm.value()).stream().map(OracleCommCalcStore::toDomain).toList();
    }

    @Override
    public CommCalcRecord transition(long calcId, CalcStatus to) {
        String current = mapper.lockStatus(calcId);
        if (current == null) {
            throw new IllegalArgumentException("존재하지 않는 calcId: " + calcId);
        }
        CalcStatus from = CalcStatus.valueOf(current);
        if (!from.canTransitionTo(to)) {
            throw new IllegalStateException(
                    "허용되지 않는 상태 전이: " + from + " → " + to + " (calcId=" + calcId + ")");
        }
        mapper.updateStatus(calcId, to.name());
        return findById(calcId).orElseThrow();
    }

    private static CommCalcMapper.Row toRow(CommCalcRecord record) {
        CommCalcMapper.Row row = new CommCalcMapper.Row();
        row.eventId = record.eventId();
        row.policyNo = record.policyNo().value();
        row.recipientType = record.recipientType().name();
        row.recipientId = record.recipientId();
        row.commType = record.commType().value();
        row.baseAmount = record.baseAmount().toLong();
        row.appliedRate = record.appliedRate() == null ? null : record.appliedRate().value();
        row.calcAmount = record.calcAmount().toLong();
        row.limitCutAmt = record.limitCutAmt().toLong();
        row.closeYm = record.closeYm().value();
        row.status = record.status().name();
        row.reversalOf = record.reversalOf();
        row.ruleVersions = record.ruleVersions();
        row.calcTrace = record.calcTrace();
        return row;
    }

    private static CommCalcRecord toDomain(CommCalcMapper.Row row) {
        return new CommCalcRecord(row.calcId, row.eventId, new PolicyNo(row.policyNo),
                RecipientType.valueOf(row.recipientType), row.recipientId,
                new CommTypeCode(row.commType), Money.won(row.baseAmount),
                row.appliedRate == null ? null : Rate.of(row.appliedRate),
                Money.won(row.calcAmount), Money.won(row.limitCutAmt), CloseYm.of(row.closeYm),
                CalcStatus.valueOf(row.status), row.reversalOf, row.ruleVersions, row.calcTrace);
    }
}
