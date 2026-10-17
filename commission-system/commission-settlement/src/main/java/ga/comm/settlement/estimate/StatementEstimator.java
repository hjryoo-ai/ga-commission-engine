package ga.comm.settlement.estimate;

import ga.comm.calc.net.NetAmountCalculator;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.clawback.ClawbackOffsetService;
import ga.comm.deferral.DeferralReleaseService;
import ga.comm.deferral.ScheduleEntry;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;
import ga.comm.domain.type.RecipientType;
import ga.comm.settlement.AdjustmentService;
import ga.comm.settlement.PayoutMath;
import ga.comm.settlement.SettleCloseStore;
import ga.comm.settlement.WithholdingTaxPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 잠정 명세 산출기(E2 ①) — "지금 이 달을 마감하고 지급하면" 설계사마다 받을 금액. 지급 런의 dry-run이지만 파이프라인·{@code PayoutService}를
 * 돌리지 않는 <b>순수 산출기</b>다(E2 Q1): 입력은 읽기 함수 묶음({@link EstimateReads})뿐이고, 금액 산식은 지급 런과 같은 {@link PayoutMath},
 * 상계는 {@link ClawbackOffsetService#previewConsume}(consume과 같은 반복), 도래 분급은 마감 훅과 같은 판정
 * ({@link DeferralReleaseService#releasable})으로 미리 본다(P1 — 마감이 도래분을 DEFERRED 레코드로 넣는다). 세율 정책은 지급 런과 같은
 * 인스턴스를 받는다(P9).
 *
 * <p>대상 설계사 = 그 달 AGENT 레코드·도래 미리보기·(AGENT 대상) 정정 중 하나라도 있는 설계사(E1 수용 심사 Q1과 같은 규칙), ID 순.
 * 정정은 지급되지 않으므로(엔진 E1-2) {@code adjustment}에만 있고 순액에는 없다. 마감·마감 중 달은 거부한다({@link MonthClosedException}).
 */
public final class StatementEstimator {

    /**
     * 설계사 한 명의 예상.
     *
     * @param records    그 달 AGENT 레코드 + 도래 미리보기 DEFERRED 가상 레코드(calcId 없음) — 발행 빌더가 같은 분류로 gross·clawbackOffset을 낸다
     * @param adjustment 그 달 귀속 정정 합
     * @param math       지급 런 산식 결과(상계 미리보기 포함)
     */
    public record Estimate(String agentId, CloseYm closeYm, List<CommCalcRecord> records, Money adjustment, PayoutMath.Result math) {
        public Estimate {
            records = List.copyOf(records);
        }
    }

    private final EstimateReads reads;
    private final WithholdingTaxPolicy taxPolicy;

    public StatementEstimator(EstimateReads reads, WithholdingTaxPolicy taxPolicy) {
        this.reads = Objects.requireNonNull(reads);
        this.taxPolicy = Objects.requireNonNull(taxPolicy);
    }

    /** 그 달 대상 설계사 전원(ID 순). */
    public List<Estimate> estimate(CloseYm ym) {
        requireOpen(ym);
        Map<String, List<CommCalcRecord>> byAgent = new TreeMap<>();
        for (CommCalcRecord r : reads.recordsOfMonth().apply(ym)) {
            if (r.recipientType() == RecipientType.AGENT) {
                byAgent.computeIfAbsent(r.recipientId(), k -> new ArrayList<>()).add(r);
            }
        }
        for (ScheduleEntry entry : reads.dueOf().apply(ym)) {
            if (DeferralReleaseService.releasable(entry, ym, reads.policyStatus())) {
                byAgent.computeIfAbsent(entry.agentId().value(), k -> new ArrayList<>()).add(deferredPreview(entry, ym));
            }
        }
        Map<String, Money> adjustments = new LinkedHashMap<>();
        for (AdjustmentService.Adjustment a : reads.adjustmentsOf().apply(ym)) {
            if (a.targetCalcId() == null) {
                continue;   // 설계사에 귀속할 수 없는 정정 — StatementConfirmed와 같은 규칙
            }
            CommCalcRecord target = reads.calcById().apply(a.targetCalcId())
                    .orElseThrow(() -> new IllegalStateException("정정 대상 계산이 없다: adjId=" + a.adjId()));
            if (target.recipientType() == RecipientType.AGENT) {
                adjustments.merge(target.recipientId(), a.amount(), Money::plus);
                byAgent.computeIfAbsent(target.recipientId(), k -> new ArrayList<>());
            }
        }
        List<Estimate> out = new ArrayList<>();
        for (Map.Entry<String, List<CommCalcRecord>> e : byAgent.entrySet()) {
            out.add(estimateOne(e.getKey(), ym, e.getValue(), adjustments.getOrDefault(e.getKey(), Money.ZERO)));
        }
        return out;
    }

    /** 설계사 한 명(그 달 대상이 아니면 비어 있다). */
    public Optional<Estimate> estimate(CloseYm ym, String agentId) {
        return estimate(ym).stream().filter(e -> e.agentId().equals(agentId)).findFirst();
    }

    private Estimate estimateOne(String agentId, CloseYm ym, List<CommCalcRecord> records, Money adjustment) {
        Money grossNet = NetAmountCalculator.netOf(records);   // 지급 런과 같은 순액(전 상태 합산)
        AgentId agent = new AgentId(agentId);
        PayoutMath.Result math = PayoutMath.settle(RecipientType.AGENT, grossNet,
                available -> ClawbackOffsetService.previewConsume(reads.offsettable().apply(agent), available), taxPolicy);
        return new Estimate(agentId, ym, records, adjustment, math);
    }

    /** 마감 훅이 넣을 DEFERRED 레코드의 금액 사본(저장하지 않는다 — calcId 없음). 값은 {@code DeferralReleaseService.release}와 같다. */
    private CommCalcRecord deferredPreview(ScheduleEntry entry, CloseYm ym) {
        CommCalcRecord source = reads.calcById().apply(entry.sourceCalcId())
                .orElseThrow(() -> new IllegalStateException("분급 원본 계산이 없습니다: " + entry.sourceCalcId()));
        return new CommCalcRecord(null, source.eventId(), entry.policyNo(), RecipientType.AGENT, entry.agentId().value(),
                CommTypeCode.DEFERRED, entry.amount(), null, entry.amount(), Money.ZERO, ym, CalcStatus.CALCULATED, null, "[]", "[]");
    }

    private void requireOpen(CloseYm ym) {
        if (reads.closeStateOf().apply(ym) != SettleCloseStore.CloseState.OPEN) {
            throw new MonthClosedException(ym);
        }
    }
}
