package ga.comm.settlement;

import ga.comm.calc.net.NetAmountCalculator;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.clawback.ClawbackOffsetService;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;
import ga.comm.domain.type.RecipientType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 지급 런 (설계서 §7 D+1~): 마감(CLOSED)된 월의 미정산 레코드를 수급자 단위로
 * ① 환수/채권 상계(ClawbackOffsetService) → ② 원천세 계산(상계 후 순지급액 기준) →
 * ③ CONFIRMED→PAID 전이하며 정산 행(SettlementRow)을 남긴다.
 *
 * <p>지급 주기 파라미터에 따라 (마감월 × 런 회차)로 월 N회 반복 실행할 수 있다(§11.12).
 * 이미 정산 행에 편입된 레코드는 다음 런의 대상에서 제외되므로 반복 실행이 무해하다.
 *
 * <p>합산 규약(§3.0): 런 대상 순액은 {@link NetAmountCalculator} 경유로만 구한다 —
 * 전 상태 합산이며, 마감 전 정정(원본 REVERSED·reversal·rebook이 같은 월에 공존)에서도
 * 이중 차감이 없다. 이 클래스에서 calcAmount를 직접 합산하지 않는다.
 */
public class PayoutService {

    private final CommCalcStore calcStore;
    private final AgentSettlementStore settlementStore;
    private final SettleCloseStore closeStore;
    private final ClawbackOffsetService offsetService;
    private final WithholdingTaxPolicy taxPolicy;

    public PayoutService(CommCalcStore calcStore, AgentSettlementStore settlementStore,
                         SettleCloseStore closeStore, ClawbackOffsetService offsetService,
                         WithholdingTaxPolicy taxPolicy) {
        this.calcStore = Objects.requireNonNull(calcStore);
        this.settlementStore = Objects.requireNonNull(settlementStore);
        this.closeStore = Objects.requireNonNull(closeStore);
        this.offsetService = Objects.requireNonNull(offsetService);
        this.taxPolicy = Objects.requireNonNull(taxPolicy);
    }

    /** 지급 런의 처리 단위 — 수급자 하나가 한 트랜잭션(항목 격리)이다. */
    public record RecipientKey(RecipientType type, String id) {
    }

    /** 지급명세 1행 (지급명세서 데이터의 원천). */
    public record PayoutStatement(
            CloseYm closeYm,
            int runSeq,
            RecipientType recipientType,
            String recipientId,
            Money payable,
            Money incomeTax,
            Money localTax,
            Money netPay
    ) {
    }

    public record RunResult(CloseYm closeYm, int runSeq, List<SettlementRow> settlements,
                            List<PayoutStatement> statements) {
    }

    /** 이번 런의 대상 수급자 — 마감월의 레코드 중 아직 어떤 정산 행에도 편입되지 않은 것. */
    public List<RecipientKey> pendingRecipients(CloseYm ym) {
        requireClosed(ym);
        return List.copyOf(unsettledByRecipient(ym).keySet());
    }

    /**
     * 수급자 1명 정산 — 호출자의 트랜잭션 경계 안에서 상계·정산행·PAID 전이가 원자적으로 닫힌다.
     * 이미 정산된 수급자면 null (반복 실행 무해).
     */
    public PayoutStatement settleOne(CloseYm ym, int runSeq, RecipientKey key) {
        requireClosed(ym);
        List<CommCalcRecord> records = unsettledByRecipient(ym).get(key);
        if (records == null || records.isEmpty()) {
            return null;
        }

        // 순액 = 공용 컴포넌트 경유 (§3.0 — 전 상태 합산, 상태 필터 금지)
        Money grossNet = NetAmountCalculator.netOf(records);
        List<Long> calcIds = records.stream().map(CommCalcRecord::calcId).toList();
        long repCalcId = calcIds.get(0);

        Money offset = Money.ZERO;
        Money carried = Money.ZERO;
        Money payable = Money.ZERO;

        if (key.type() == RecipientType.AGENT) {
            if (grossNet.isNegative()) {
                offsetService.capitalize(new AgentId(key.id()), grossNet, repCalcId);
                carried = grossNet.abs();
            } else if (grossNet.isPositive()) {
                offset = offsetService.consume(new AgentId(key.id()), grossNet, repCalcId);
                payable = grossNet.minus(offset);
            }
        } else {
            payable = grossNet.max(Money.ZERO);
            if (grossNet.isNegative()) {
                carried = grossNet.abs();  // 조직 음수는 이월 관리 (채권화는 사규 확인)
            }
        }

        settlementStore.saveAll(List.of(new SettlementRow(ym, runSeq, key.type(), key.id(),
                grossNet, offset, carried, payable, calcIds)));

        for (CommCalcRecord record : records) {
            if (record.status() == CalcStatus.CONFIRMED) {
                calcStore.transition(record.calcId(), CalcStatus.PAID);
            }
        }

        WithholdingTaxPolicy.Withholding tax = taxPolicy.taxOn(payable);
        return new PayoutStatement(ym, runSeq, key.type(), key.id(), payable,
                tax.incomeTax(), tax.localTax(), payable.minus(tax.total()));
    }

    /** 지급 런 전체 실행 — 배치 밖(테스트·수동 운영)에서 쓰는 편의 메서드. */
    public RunResult run(CloseYm ym, int runSeq) {
        List<PayoutStatement> statements = new ArrayList<>();
        for (RecipientKey key : pendingRecipients(ym)) {
            PayoutStatement statement = settleOne(ym, runSeq, key);
            if (statement != null) {
                statements.add(statement);
            }
        }
        List<SettlementRow> rows = settlementStore.findByCloseYm(ym).stream()
                .filter(r -> r.runSeq() == runSeq)
                .toList();
        return new RunResult(ym, runSeq, rows, statements);
    }

    private void requireClosed(CloseYm ym) {
        if (closeStore.stateOf(ym) != SettleCloseStore.CloseState.CLOSED) {
            throw new IllegalStateException("마감되지 않은 월은 지급할 수 없습니다: " + ym);
        }
    }

    private Map<RecipientKey, List<CommCalcRecord>> unsettledByRecipient(CloseYm ym) {
        Set<Long> settled = new HashSet<>();
        for (SettlementRow row : settlementStore.findByCloseYm(ym)) {
            settled.addAll(row.calcIds());
        }
        Map<RecipientKey, List<CommCalcRecord>> byRecipient = new LinkedHashMap<>();
        for (CommCalcRecord record : calcStore.findByCloseYm(ym)) {
            if (settled.contains(record.calcId())) {
                continue;
            }
            byRecipient.computeIfAbsent(new RecipientKey(record.recipientType(), record.recipientId()),
                    k -> new ArrayList<>()).add(record);
        }
        return byRecipient;
    }
}
