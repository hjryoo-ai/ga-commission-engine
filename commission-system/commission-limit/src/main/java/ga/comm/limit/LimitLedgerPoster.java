package ga.comm.limit;

import ga.comm.calc.CalcContext;
import ga.comm.calc.CalcResultListener;

import java.util.List;
import java.util.Objects;

/**
 * COMM_CALC 저장 직후 한도 원장 전기 — 게이트가 예약한 금액을 확정된 calcId로 DTL에 남긴다.
 * DB 구현에서는 계산 저장과 같은 트랜잭션에서 실행된다.
 */
public class LimitLedgerPoster implements CalcResultListener {

    private final LimitLedgerStore ledgerStore;

    public LimitLedgerPoster(LimitLedgerStore ledgerStore) {
        this.ledgerStore = Objects.requireNonNull(ledgerStore);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onPersisted(CalcContext ctx, List<PersistedLine> lines) {
        LimitLedger ledger = ctx.attachment(LimitGateStep.ATTACH_LEDGER, LimitLedger.class)
                .orElse(null);
        if (ledger == null) {
            return;
        }
        List<LimitGateStep.PendingPost> pending =
                ctx.attachment(LimitGateStep.ATTACH_PENDING_POSTS, List.class)
                        .map(l -> (List<LimitGateStep.PendingPost>) l)
                        .orElse(List.of());

        for (LimitGateStep.PendingPost post : pending) {
            PersistedLine persisted = lines.stream()
                    .filter(p -> p.lineIndex() == post.lineIndex())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "전기 대상 라인이 저장되지 않았습니다: lineIndex=" + post.lineIndex()));
            ledger.post(persisted.record().calcId(), post.amount());
        }
        ledgerStore.save(ledger);
    }
}
