package ga.comm.infra.store;

import ga.comm.calc.store.PolicyEventStore;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;
import ga.comm.domain.type.EventType;
import ga.comm.domain.type.ProcessStatus;
import ga.comm.infra.JsonMaps;
import ga.comm.infra.mapper.PolicyEventMapper;
import org.springframework.dao.DuplicateKeyException;

import java.util.Objects;
import java.util.Optional;

/**
 * POLICY_EVENT Oracle 어댑터 — event_key 멱등 저장.
 * 동시 수신 경합은 uq_policy_event_key 위반 → 재조회로 직렬화한다.
 */
public class OraclePolicyEventStore implements PolicyEventStore {

    private final PolicyEventMapper mapper;

    public OraclePolicyEventStore(PolicyEventMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public UpsertResult upsertByKey(PolicyEvent event) {
        PolicyEventMapper.Row existing = mapper.findByKey(event.eventKey());
        if (existing != null) {
            return new UpsertResult(toDomain(existing), true, ProcessStatus.valueOf(existing.processStatus));
        }
        PolicyEventMapper.Row row = toRow(event);
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException raced) {
            PolicyEventMapper.Row winner = mapper.findByKey(event.eventKey());
            return new UpsertResult(toDomain(winner), true, ProcessStatus.valueOf(winner.processStatus));
        }
        return new UpsertResult(event.withEventId(row.eventId), false, ProcessStatus.PENDING);
    }

    @Override
    public Optional<PolicyEvent> findById(long eventId) {
        return Optional.ofNullable(mapper.findById(eventId)).map(OraclePolicyEventStore::toDomain);
    }

    @Override
    public void markProcessed(long eventId) {
        requireUpdated(mapper.updateStatus(eventId, ProcessStatus.PROCESSED.name(), null), eventId);
    }

    @Override
    public void markFailed(long eventId, String reason) {
        requireUpdated(mapper.updateStatus(eventId, ProcessStatus.FAILED.name(), truncate(reason)), eventId);
    }

    @Override
    public ProcessStatus statusOf(long eventId) {
        String status = mapper.statusOf(eventId);
        if (status == null) {
            throw new IllegalArgumentException("존재하지 않는 eventId: " + eventId);
        }
        return ProcessStatus.valueOf(status);
    }

    @Override
    public long unprocessedCount() {
        return mapper.unprocessedCount();
    }

    private static void requireUpdated(int updated, long eventId) {
        if (updated == 0) {
            throw new IllegalArgumentException("존재하지 않는 eventId: " + eventId);
        }
    }

    /** fail_reason VARCHAR2(400)은 BYTE 시맨틱 — 한글 3바이트 기준 안전 길이로 절단한다. */
    private static String truncate(String reason) {
        if (reason == null || reason.isEmpty()) {
            return null; // Oracle은 ''를 NULL로 접는다 — 진단 텍스트라 구분하지 않는다 (§8.7)
        }
        return reason.length() <= 120 ? reason : reason.substring(0, 120);
    }

    private static PolicyEventMapper.Row toRow(PolicyEvent event) {
        PolicyEventMapper.Row row = new PolicyEventMapper.Row();
        row.eventKey = event.eventKey();
        row.policyNo = event.policyNo().value();
        row.insurerCd = event.insurerCd().value();
        row.productKey = event.productKey().value();
        row.eventType = event.eventType().name();
        row.eventDate = event.eventDate();
        row.contractDate = event.contractDate();
        row.agentId = event.agentId().value();
        row.monthlyPremium = event.monthlyPremium().toLong();
        row.paymentAmount = event.paymentAmount() == null ? null : event.paymentAmount().toLong();
        row.installmentNo = event.installmentNo();
        row.payload = JsonMaps.write(event.attributes());
        row.processStatus = ProcessStatus.PENDING.name();
        return row;
    }

    private static PolicyEvent toDomain(PolicyEventMapper.Row row) {
        return new PolicyEvent(row.eventId, row.eventKey, new PolicyNo(row.policyNo),
                new InsurerCode(row.insurerCd), new ProductKey(row.productKey),
                EventType.valueOf(row.eventType), row.eventDate, row.contractDate,
                new AgentId(row.agentId), Money.won(row.monthlyPremium),
                row.paymentAmount == null ? null : Money.won(row.paymentAmount),
                row.installmentNo, JsonMaps.read(row.payload));
    }
}
