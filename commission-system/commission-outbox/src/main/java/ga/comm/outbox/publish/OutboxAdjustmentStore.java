package ga.comm.outbox.publish;

import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.RecipientType;
import ga.comm.settlement.AdjustmentService;

import java.util.List;
import java.util.Objects;

/**
 * 마감 후 정정마다 {@code AdjustmentPosted}(D-12, E1 P5 — 엔진 {@code COMM_CALC}에는 정정 행이 없으므로 이것 하나). 설계사는 대상 계산의
 * 수급자이고, 대상이 ORG면 내지 않는다(D-3). 대상 없는 정정은 포털 계약이 받지 않으므로 실패한다({@link PortalPayloads#adjustmentPosted}) —
 * {@link AdjustmentService#adjust}는 늘 대상을 넘긴다.
 */
public final class OutboxAdjustmentStore implements AdjustmentService.AdjustmentStore {

    public static final String TYPE = "AdjustmentPosted";
    public static final String KIND = "ADJUSTMENT";

    private final AdjustmentService.AdjustmentStore delegate;
    private final CommCalcStore calcStore;
    private final OutboxPublisher publisher;

    public OutboxAdjustmentStore(AdjustmentService.AdjustmentStore delegate, CommCalcStore calcStore, OutboxPublisher publisher) {
        this.delegate = Objects.requireNonNull(delegate);
        this.calcStore = Objects.requireNonNull(calcStore);
        this.publisher = Objects.requireNonNull(publisher);
    }

    @Override
    public AdjustmentService.Adjustment create(Long targetCalcId, String reason, Money amount, CloseYm closeYm, String approvedBy) {
        AdjustmentService.Adjustment created = delegate.create(targetCalcId, reason, amount, closeYm, approvedBy);
        if (targetCalcId == null) {
            PortalPayloads.adjustmentPosted(created, null);   // 계약 위반 — 실패(업무 트랜잭션도 롤백)
        }
        CommCalcRecord target = calcStore.findById(targetCalcId).orElseThrow(() -> new IllegalStateException(
                "정정 대상 계산이 없다: targetCalcId=" + targetCalcId));
        if (target.recipientType() == RecipientType.AGENT) {
            publisher.publish(TYPE, KIND, Long.toString(created.adjId()),
                    PortalPayloads.adjustmentPosted(created, target.recipientId()));
        }
        return created;
    }

    @Override
    public List<AdjustmentService.Adjustment> findByCloseYm(CloseYm closeYm) {
        return delegate.findByCloseYm(closeYm);
    }
}
