package ga.comm.outbox.feed;

import com.fasterxml.jackson.databind.node.ObjectNode;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.RecipientType;
import ga.comm.outbox.OutboxStore;
import ga.comm.outbox.publish.PortalPayloads;
import ga.comm.outbox.publish.StatementPublisher;
import ga.comm.settlement.AdjustmentService;
import ga.comm.settlement.PayoutMath;
import ga.comm.settlement.PayoutStatementStore;
import ga.comm.settlement.SettleCloseStore;
import ga.comm.settlement.WithholdingTaxPolicy;
import ga.comm.settlement.estimate.MonthClosedException;
import ga.comm.settlement.estimate.StatementEstimator;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * 명세 조회(E2 ③, 포털 계약 {@code getStatement}) — 발행하지 않는다(조회는 이벤트가 아니다). 읽기 전용 트랜잭션 하나에서.
 * <ul>
 *   <li>{@code ESTIMATE}: 잠정 명세 산출기를 지금 실행한 결과({@code StatementEstimated} 모양). {@code basisSeq} = 현재 최대 부여 순번을
 *       <b>부여 없이</b> 읽은 값(E2 승인 Q3 — 하한), {@code asOf} = 시계. 마감·마감 중 달 → {@link MonthClosedException}(422
 *       {@code MONTH_CLOSED}). 그 달 대상이 아닌 설계사는 0의 명세.</li>
 *   <li>{@code CONFIRMED}: 업무 표 + 발행과 같은 빌더(Q4) — {@code StatementConfirmed} payload, 그 달 지급 런이 끝났으면 {@code withholdingTax}·
 *       {@code net}에 그 달 지급 런의 합({@code PAYOUT_STATEMENT}, 계약 v1.3 R8). 마감 전({@code CLOSED} 아님) → {@link NotConfirmed}(404
 *       {@code STATEMENT_NOT_CONFIRMED}). 같은 달 {@code StatementConfirmed}·{@code PayoutCompleted} 이벤트와 필드 단위로 같다(C6).</li>
 * </ul>
 * 엔진에 없는 설계사 → {@link AgentNotFound}(404 {@code AGENT_NOT_FOUND}).
 */
public final class StatementQuery {

    /** 404 AGENT_NOT_FOUND. */
    public static final class AgentNotFound extends RuntimeException {
        public AgentNotFound(String agentId) {
            super("엔진에 없는 설계사: " + agentId);
        }
    }

    /** 404 STATEMENT_NOT_CONFIRMED. */
    public static final class NotConfirmed extends RuntimeException {
        public NotConfirmed(CloseYm ym) {
            super("아직 마감되지 않은 달: " + ym);
        }
    }

    private final StatementEstimator estimator;
    private final CommCalcStore calcStore;
    private final AdjustmentService.AdjustmentStore adjustments;
    private final SettleCloseStore closeStore;
    private final PayoutStatementStore payoutStatements;
    private final AgentMasterReader agents;
    private final OutboxStore outbox;
    private final EventFeed.Tx readOnlyTx;
    private final WithholdingTaxPolicy taxPolicy;
    private final Clock clock;

    public StatementQuery(StatementEstimator estimator, CommCalcStore calcStore, AdjustmentService.AdjustmentStore adjustments,
                          SettleCloseStore closeStore, PayoutStatementStore payoutStatements, AgentMasterReader agents, OutboxStore outbox,
                          EventFeed.Tx readOnlyTx, WithholdingTaxPolicy taxPolicy, Clock clock) {
        this.estimator = Objects.requireNonNull(estimator);
        this.calcStore = Objects.requireNonNull(calcStore);
        this.adjustments = Objects.requireNonNull(adjustments);
        this.closeStore = Objects.requireNonNull(closeStore);
        this.payoutStatements = Objects.requireNonNull(payoutStatements);
        this.agents = Objects.requireNonNull(agents);
        this.outbox = Objects.requireNonNull(outbox);
        this.readOnlyTx = Objects.requireNonNull(readOnlyTx);
        this.taxPolicy = Objects.requireNonNull(taxPolicy);
        this.clock = Objects.requireNonNull(clock);
    }

    public ObjectNode estimate(String agentId, CloseYm ym) {
        return readOnlyTx.run(() -> {
            requireAgent(agentId);
            long basisSeq = outbox.headSeq();   // 부여 없이(Q3)
            OffsetDateTime asOf = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
            StatementEstimator.Estimate e = estimator.estimate(ym, agentId).orElseGet(() -> new StatementEstimator.Estimate(agentId, ym,
                    List.of(), Money.ZERO, PayoutMath.settle(RecipientType.AGENT, Money.ZERO, available -> Money.ZERO, taxPolicy)));
            return PortalPayloads.statementEstimated(e, basisSeq, asOf);
        });
    }

    public ObjectNode confirmed(String agentId, CloseYm ym) {
        return readOnlyTx.run(() -> {
            requireAgent(agentId);
            if (closeStore.stateOf(ym) != SettleCloseStore.CloseState.CLOSED) {
                throw new NotConfirmed(ym);
            }
            OffsetDateTime closedAt = closeStore.closedAtOf(ym).orElseThrow(() -> new IllegalStateException("CLOSED인데 closed_at이 없다: " + ym))
                    .atZone(clock.getZone()).toOffsetDateTime();
            List<CommCalcRecord> records = calcStore.findByCloseYm(ym).stream()
                    .filter(r -> r.recipientType() == RecipientType.AGENT && r.recipientId().equals(agentId)).toList();
            Money adjustment = StatementPublisher.adjustmentByAgent(adjustments, calcStore, ym).getOrDefault(agentId, Money.ZERO);
            ObjectNode p = PortalPayloads.statementConfirmed(agentId, ym, records, adjustment, closedAt);
            List<PayoutStatementStore.Saved> paid = payoutStatements.findByCloseYm(ym).stream()
                    .filter(s -> s.statement().recipientType() == RecipientType.AGENT && s.statement().recipientId().equals(agentId)).toList();
            if (!paid.isEmpty()) {
                Money tax = Money.ZERO;
                Money net = Money.ZERO;
                for (PayoutStatementStore.Saved s : paid) {
                    tax = tax.plus(s.statement().incomeTax()).plus(s.statement().localTax());
                    net = net.plus(s.statement().netPay());
                }
                p.put("withholdingTax", tax.toLong());
                p.put("net", net.toLong());
            }
            return p;
        });
    }

    private void requireAgent(String agentId) {
        if (agents.find(agentId).isEmpty()) {
            throw new AgentNotFound(agentId);
        }
    }
}
