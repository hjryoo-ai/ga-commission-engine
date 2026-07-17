package ga.comm.infra.store;

import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.infra.mapper.AdjustmentMapper;
import ga.comm.settlement.AdjustmentService;

import java.util.Objects;

/** ADJUSTMENT Oracle 어댑터 — INSERT만 존재한다 (마감 후 정정, §6.4). */
public class OracleAdjustmentStore implements AdjustmentService.AdjustmentStore {

    private final AdjustmentMapper mapper;

    public OracleAdjustmentStore(AdjustmentMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public AdjustmentService.Adjustment create(Long targetCalcId, String reason, Money amount,
                                               CloseYm closeYm, String approvedBy) {
        AdjustmentMapper.Row row = new AdjustmentMapper.Row();
        row.targetCalcId = targetCalcId;
        row.reason = reason;
        row.amount = amount.toLong();
        row.closeYm = closeYm.value();
        row.approvedBy = approvedBy;
        mapper.insert(row);
        return new AdjustmentService.Adjustment(row.adjId, targetCalcId, reason, amount, closeYm,
                approvedBy);
    }
}
