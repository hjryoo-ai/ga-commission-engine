package ga.comm.outbox.publish;

import ga.comm.calc.store.CommCalcStore;
import ga.comm.calc.store.PolicyEventStore;
import ga.comm.clawback.ClawbackReceivableStore;
import ga.comm.deferral.DeferralScheduleStore;
import ga.comm.limit.LimitLedgerStore;
import ga.comm.settlement.AdjustmentService;
import ga.comm.settlement.MonthCloseListener;
import ga.comm.settlement.PayoutListener;

/**
 * 발행 지점 조립(설계서 §6.8 발행 지점 표) — 저장소 여섯을 감싸고 리스너 둘을 만든다. 앱 조립·Oracle IT·아웃박스 시험이 같은 조립을 쓴다.
 * 읽기용 의존(이벤트 → 계산의 상품·계약 요약, 정정 → 대상 계산의 수급자)은 감싸지 않은 저장소를 받는다 — 같은 트랜잭션, 같은 행.
 */
public record PublishingStores(PolicyEventStore events, CommCalcStore calcs, LimitLedgerStore ledgers,
                               DeferralScheduleStore schedules, ClawbackReceivableStore receivables,
                               AdjustmentService.AdjustmentStore adjustments,
                               MonthCloseListener statementPublisher, PayoutListener payoutPublisher) {

    public static PublishingStores wrap(OutboxPublisher publisher, PolicyEventStore events, CommCalcStore calcs,
                                        LimitLedgerStore ledgers, DeferralScheduleStore schedules,
                                        ClawbackReceivableStore receivables, AdjustmentService.AdjustmentStore adjustments) {
        return new PublishingStores(
                new OutboxPolicyEventStore(events, publisher),
                new OutboxCommCalcStore(calcs, events, publisher),
                new OutboxLimitLedgerStore(ledgers, publisher),
                new OutboxDeferralScheduleStore(schedules, publisher),
                new OutboxClawbackReceivableStore(receivables, publisher),
                new OutboxAdjustmentStore(adjustments, calcs, publisher),
                new StatementPublisher(adjustments, calcs, publisher),
                new PayoutPublisher(publisher));
    }
}
