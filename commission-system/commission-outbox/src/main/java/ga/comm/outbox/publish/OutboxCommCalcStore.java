package ga.comm.outbox.publish;

import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.calc.store.PolicyEventStore;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;
import ga.comm.domain.type.RecipientType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code COMM_CALC} insert마다 {@code CommissionCalculated}(설계서 §6.8, E1 P12) — 계산·정정 reversal·분급 도래, 세 insert 경로가 전부 이
 * 포트를 거치므로 빠짐이 구조로 없다. 수급자가 AGENT인 레코드만(D-3 — ORG 제외). 같은 호출 = 같은 트랜잭션(C3).
 * 상품·계약 요약은 레코드의 {@code event_id}로 같은 트랜잭션에서 읽는다.
 */
public final class OutboxCommCalcStore implements CommCalcStore {

    public static final String TYPE = "CommissionCalculated";
    public static final String KIND = "COMM_CALC";

    private final CommCalcStore delegate;
    private final PolicyEventStore events;
    private final OutboxPublisher publisher;

    public OutboxCommCalcStore(CommCalcStore delegate, PolicyEventStore events, OutboxPublisher publisher) {
        this.delegate = Objects.requireNonNull(delegate);
        this.events = Objects.requireNonNull(events);
        this.publisher = Objects.requireNonNull(publisher);
    }

    @Override
    public CommCalcRecord insert(CommCalcRecord record) {
        CommCalcRecord saved = delegate.insert(record);
        if (saved.recipientType() == RecipientType.AGENT) {
            PolicyEvent event = events.findById(saved.eventId()).orElseThrow(() -> new IllegalStateException(
                    "계산 레코드의 정책 이벤트가 없다: calcId=" + saved.calcId() + " eventId=" + saved.eventId()));
            publisher.publish(TYPE, KIND, Long.toString(saved.calcId()), PortalPayloads.commissionCalculated(saved, event));
        }
        return saved;
    }

    @Override
    public Optional<CommCalcRecord> findById(long calcId) {
        return delegate.findById(calcId);
    }

    @Override
    public List<CommCalcRecord> findByEventId(long eventId) {
        return delegate.findByEventId(eventId);
    }

    @Override
    public List<CommCalcRecord> findByPolicyAndRecipient(PolicyNo policyNo, String recipientId) {
        return delegate.findByPolicyAndRecipient(policyNo, recipientId);
    }

    @Override
    public List<CommCalcRecord> findByCloseYm(CloseYm closeYm) {
        return delegate.findByCloseYm(closeYm);
    }

    /** 상태 전이는 이벤트가 아니다 — 포털은 마감·지급 이벤트로 안다(계약 v1). */
    @Override
    public CommCalcRecord transition(long calcId, CalcStatus to) {
        return delegate.transition(calcId, to);
    }
}
