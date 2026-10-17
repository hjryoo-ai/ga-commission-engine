package ga.comm.outbox.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.ValidationMessage;
import ga.comm.outbox.EnvelopeJson;
import ga.comm.outbox.OutboxEvent;
import ga.comm.outbox.OutboxStore;
import ga.comm.outbox.StoredEvent;
import ga.comm.outbox.contract.PortalContract;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 전역 검증기(Phase E1 C1) — 적재되는 이벤트를 <b>그 자리에서</b> 포털 계약(Envelope + type별 payload)으로 검증하는 시험용 데코레이터.
 * 순번은 아직 없으므로 자리표시 {@code seq = 1}로 Envelope를 만든다(순번 형식은 계약이 1 이상 정수만 본다). 위반이면 {@link AssertionError}로
 * 그 시험을 실패시키고, 통과하면 JVM 전역 집계({@link OutboxValidation})에 타입·설계사를 더한다 — 시험 태스크 끝에 집계가 빌드 산출물이 된다.
 */
public final class ValidatingOutboxStore implements OutboxStore {

    private final OutboxStore delegate;

    public ValidatingOutboxStore(OutboxStore delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public void append(OutboxEvent event) {
        JsonNode envelope = EnvelopeJson.render(event, 1L);
        Set<ValidationMessage> violations = PortalContract.get().validateEnvelope(envelope);
        if (!violations.isEmpty()) {
            OutboxValidation.violation(event.type());
            throw new AssertionError("포털 계약 위반 이벤트 " + event.type() + " " + event.aggregateKind() + "/" + event.aggregateId()
                    + ": " + violations + "\n" + envelope.toPrettyString());
        }
        delegate.append(event);
        OutboxValidation.validated(event.type(), envelope.path("payload").path("agentId").asText(null));
    }

    @Override
    public int assignPending(int max) {
        return delegate.assignPending(max);
    }

    @Override
    public List<StoredEvent> readAssignedAfter(long afterSeq, int limit) {
        return delegate.readAssignedAfter(afterSeq, limit);
    }

    @Override
    public long headSeq() {
        return delegate.headSeq();
    }

    @Override
    public boolean contains(String type, String aggregateKind, String aggregateId) {
        return delegate.contains(type, aggregateKind, aggregateId);
    }
}
