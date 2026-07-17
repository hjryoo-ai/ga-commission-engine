package ga.comm.settlement;

import ga.comm.calc.store.CloseStatusProvider;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;

import java.util.Objects;

/**
 * 마감 후 정정 (ADJUSTMENT, 설계서 §5.9/§6.4) — 반드시 익월(이후 첫 OPEN 월) 귀속.
 * 미마감월 정정은 이 서비스가 아니라 Reversal &amp; Rebook으로 처리한다.
 */
public class AdjustmentService {

    public record Adjustment(long adjId, Long targetCalcId, String reason, Money amount,
                             CloseYm closeYm, String approvedBy) {
    }

    public interface AdjustmentStore {
        Adjustment create(Long targetCalcId, String reason, Money amount, CloseYm closeYm,
                          String approvedBy);
    }

    private final CommCalcStore calcStore;
    private final CloseStatusProvider closeStatus;
    private final AdjustmentStore adjustmentStore;

    public AdjustmentService(CommCalcStore calcStore, CloseStatusProvider closeStatus,
                             AdjustmentStore adjustmentStore) {
        this.calcStore = Objects.requireNonNull(calcStore);
        this.closeStatus = Objects.requireNonNull(closeStatus);
        this.adjustmentStore = Objects.requireNonNull(adjustmentStore);
    }

    public Adjustment adjust(long targetCalcId, String reason, Money amount, String approvedBy) {
        CommCalcRecord target = calcStore.findById(targetCalcId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 calcId: " + targetCalcId));
        if (!closeStatus.isClosed(target.closeYm())) {
            throw new IllegalStateException(
                    "미마감월(" + target.closeYm() + ") 건은 ADJUSTMENT가 아니라 Reversal&Rebook으로 정정합니다");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("정정 사유는 필수입니다");
        }

        CloseYm attribution = closeStatus.attributionFor(target.closeYm());
        return adjustmentStore.create(targetCalcId, reason, amount, attribution, approvedBy);
    }
}
