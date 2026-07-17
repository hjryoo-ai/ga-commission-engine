package ga.comm.settlement.fixture;

import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.settlement.AdjustmentService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 인메모리 ADJUSTMENT 저장소. */
public class InMemoryAdjustmentStore implements AdjustmentService.AdjustmentStore {

    private final List<AdjustmentService.Adjustment> adjustments = new ArrayList<>();
    private final AtomicLong idSeq = new AtomicLong(0);

    @Override
    public synchronized AdjustmentService.Adjustment create(Long targetCalcId, String reason,
                                                            Money amount, CloseYm closeYm,
                                                            String approvedBy) {
        AdjustmentService.Adjustment adjustment = new AdjustmentService.Adjustment(
                idSeq.incrementAndGet(), targetCalcId, reason, amount, closeYm, approvedBy);
        adjustments.add(adjustment);
        return adjustment;
    }

    public synchronized List<AdjustmentService.Adjustment> all() {
        return List.copyOf(adjustments);
    }
}
