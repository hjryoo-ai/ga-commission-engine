package ga.comm.limit;

import ga.comm.calc.revision.RevisionService;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.money.Money;
import ga.comm.domain.type.RecipientType;

import java.util.Objects;
import java.util.Optional;

/**
 * 취소분개 시 한도 원장 원복 — 원본 calcId로 전기된 금액을 정확히 반대 부호로 전기한다.
 * 유형 판단이 필요 없다: DTL이 calcId 단위로 근거를 들고 있으므로 그 합을 되돌리면 된다.
 */
public class LimitReversalHook implements RevisionService.ReversalHook {

    private final LimitLedgerStore ledgerStore;

    public LimitReversalHook(LimitLedgerStore ledgerStore) {
        this.ledgerStore = Objects.requireNonNull(ledgerStore);
    }

    @Override
    public void onReversal(CommCalcRecord original, CommCalcRecord reversal) {
        if (original.recipientType() != RecipientType.AGENT) {
            return;
        }
        Optional<LimitLedger> ledgerOpt = ledgerStore.find(original.policyNo(),
                new AgentId(original.recipientId()));
        if (ledgerOpt.isEmpty()) {
            return;
        }
        LimitLedger ledger = ledgerOpt.get();

        Money postedForOriginal = ledger.postings().stream()
                .filter(p -> p.calcId() == original.calcId())
                .map(LimitLedger.Posting::amount)
                .reduce(Money.ZERO, Money::plus);

        if (!postedForOriginal.isZero()) {
            ledger.post(reversal.calcId(), postedForOriginal.negate());
            ledgerStore.save(ledger);
        }
    }
}
