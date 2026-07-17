package ga.comm.settlement;

import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.RecipientType;

import java.util.List;
import java.util.Objects;

/**
 * 지급 런에서 확정된 수급자별 정산 내역 (설계서 §7 D+1~).
 * 상계·원천세는 마감이 아니라 지급 런 단위로 수행되므로(§6.3) 정산 행은 (마감월, 런 회차)에 귀속된다.
 *
 * @param runSeq            지급 런 회차 (월 N회 지급 주기 — §11.12)
 * @param grossNet          이번 런 대상 레코드의 순액 (전 상태 합산 §3.0, 상계 전)
 * @param receivableOffset  기존 환수 채권 자동 상계액
 * @param carriedReceivable 순액이 음수여서 채권으로 이월된 금액 (양수 표기)
 * @param payable           지급 대상액 = max(0, grossNet) − receivableOffset
 */
public record SettlementRow(
        CloseYm closeYm,
        int runSeq,
        RecipientType recipientType,
        String recipientId,
        Money grossNet,
        Money receivableOffset,
        Money carriedReceivable,
        Money payable,
        List<Long> calcIds
) {
    public SettlementRow {
        Objects.requireNonNull(closeYm);
        Objects.requireNonNull(recipientType);
        Objects.requireNonNull(recipientId);
        Objects.requireNonNull(grossNet);
        Objects.requireNonNull(receivableOffset);
        Objects.requireNonNull(carriedReceivable);
        Objects.requireNonNull(payable);
        if (runSeq < 1) {
            throw new IllegalArgumentException("지급 런 회차는 1 이상이어야 합니다: " + runSeq);
        }
        calcIds = List.copyOf(calcIds);
    }
}
