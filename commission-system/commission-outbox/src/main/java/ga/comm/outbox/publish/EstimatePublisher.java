package ga.comm.outbox.publish;

import ga.comm.settlement.estimate.StatementEstimator;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 잠정 명세 발행(E2 ①) — 산출기 결과 하나를 {@code StatementEstimated} 한 행으로 적재한다. 업무 표는 건드리지 않는다(아웃박스 행 하나만 —
 * C1). 같은 (설계사, 월)에 다시 내면 다시 적재한다(예상은 시점마다의 사실 — 포털은 최신을 쓴다). 마감·마감 중 달은 산출기가 이미 거부한다.
 */
public final class EstimatePublisher {

    public static final String TYPE = "StatementEstimated";

    private final OutboxPublisher publisher;

    public EstimatePublisher(OutboxPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher);
    }

    public void publish(StatementEstimator.Estimate estimate, long basisSeq, OffsetDateTime asOf) {
        publisher.publish(TYPE, StatementPublisher.KIND, PortalPayloads.statementAggregateId(estimate.agentId(), estimate.closeYm()),
                PortalPayloads.statementEstimated(estimate, basisSeq, asOf), asOf);
    }
}
