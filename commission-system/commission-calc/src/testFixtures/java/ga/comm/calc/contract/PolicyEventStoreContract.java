package ga.comm.calc.contract;

import ga.comm.calc.store.PolicyEventStore;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;
import ga.comm.domain.type.EventType;
import ga.comm.domain.type.ProcessStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PolicyEventStore 계약 테스트 (설계서 §8.7) — 인메모리 레퍼런스와 Oracle 어댑터가
 * 동일 스위트를 상속해 동작 동등성을 증명한다. 핵심 계약: event_key 멱등 저장.
 */
public abstract class PolicyEventStoreContract {

    protected abstract PolicyEventStore store();

    /** DB 구현은 트랜잭션으로 감싼다. 인메모리는 그대로 실행. */
    protected <T> T inTx(Supplier<T> work) {
        return work.get();
    }

    private PolicyEvent event(String key) {
        return new PolicyEvent(null, key, new PolicyNo("POL-CT-1"), new InsurerCode("SAMLIFE"),
                new ProductKey("WHOLE-LIFE-20Y"), EventType.NEW,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), new AgentId("A-1001"),
                Money.won(300_000), Money.won(300_000), 1,
                Map.of("channel", "GA", "empty_attr", ""));
    }

    @Test
    void 신규_이벤트는_ID가_부여되고_PENDING으로_저장된다() {
        PolicyEventStore.UpsertResult result = inTx(() -> store().upsertByKey(event("CT-KEY-1")));

        assertThat(result.duplicate()).isFalse();
        assertThat(result.status()).isEqualTo(ProcessStatus.PENDING);
        assertThat(result.event().eventId()).isNotNull();
        assertThat(store().statusOf(result.event().eventId())).isEqualTo(ProcessStatus.PENDING);
    }

    @Test
    void 같은_키_재수신은_기존_이벤트를_반환하고_재저장하지_않는다() {
        PolicyEvent original = event("CT-KEY-DUP");
        PolicyEventStore.UpsertResult first = inTx(() -> store().upsertByKey(original));
        store().markProcessed(first.event().eventId());

        // 같은 키에 다른 내용이 와도 저장분이 기준이다 (멱등)
        PolicyEvent conflicting = new PolicyEvent(null, "CT-KEY-DUP", new PolicyNo("POL-OTHER"),
                new InsurerCode("SAMLIFE"), new ProductKey("WHOLE-LIFE-20Y"), EventType.NEW,
                LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 9), new AgentId("A-9999"),
                Money.won(999_999), null, null, Map.of());
        PolicyEventStore.UpsertResult second = inTx(() -> store().upsertByKey(conflicting));

        assertThat(second.duplicate()).isTrue();
        assertThat(second.status()).isEqualTo(ProcessStatus.PROCESSED);
        assertThat(second.event().eventId()).isEqualTo(first.event().eventId());
        assertThat(second.event().policyNo()).isEqualTo(original.policyNo());
        assertThat(second.event().monthlyPremium()).isEqualTo(original.monthlyPremium());
    }

    @Test
    void 저장된_이벤트는_모든_필드가_재조회에서_보존된다() {
        PolicyEvent original = event("CT-KEY-RT");
        inTx(() -> store().upsertByKey(original));

        PolicyEvent reloaded = inTx(() -> store().upsertByKey(original)).event();

        assertThat(reloaded.eventKey()).isEqualTo(original.eventKey());
        assertThat(reloaded.policyNo()).isEqualTo(original.policyNo());
        assertThat(reloaded.insurerCd()).isEqualTo(original.insurerCd());
        assertThat(reloaded.productKey()).isEqualTo(original.productKey());
        assertThat(reloaded.eventType()).isEqualTo(original.eventType());
        assertThat(reloaded.eventDate()).isEqualTo(original.eventDate());
        assertThat(reloaded.contractDate()).isEqualTo(original.contractDate());
        assertThat(reloaded.agentId()).isEqualTo(original.agentId());
        assertThat(reloaded.monthlyPremium()).isEqualTo(original.monthlyPremium());
        assertThat(reloaded.paymentAmount()).isEqualTo(original.paymentAmount());
        assertThat(reloaded.installmentNo()).isEqualTo(original.installmentNo());
        // 속성 맵은 JSON payload로 왕복한다. 빈 문자열 값도 JSON에서는 보존된다
        // (Oracle의 ''=NULL 접힘은 VARCHAR2 컬럼에만 해당 — CLOB JSON은 영향 없음).
        assertThat(reloaded.attributes()).isEqualTo(original.attributes());
    }

    @Test
    void ID_단건_조회는_저장된_이벤트를_복원하고_없으면_empty다() {
        PolicyEvent original = event("CT-KEY-BYID");
        long id = inTx(() -> store().upsertByKey(original)).event().eventId();

        PolicyEvent reloaded = inTx(() -> store().findById(id)).orElseThrow();
        assertThat(reloaded.eventKey()).isEqualTo(original.eventKey());
        assertThat(reloaded.policyNo()).isEqualTo(original.policyNo());
        assertThat(reloaded.monthlyPremium()).isEqualTo(original.monthlyPremium());
        assertThat(reloaded.attributes()).isEqualTo(original.attributes());

        assertThat(inTx(() -> store().findById(-424242L))).isEmpty();
    }

    @Test
    void 처리_상태_전이와_미처리_카운트가_일관된다() {
        long id1 = inTx(() -> store().upsertByKey(event("CT-KEY-S1"))).event().eventId();
        long id2 = inTx(() -> store().upsertByKey(event("CT-KEY-S2"))).event().eventId();
        long id3 = inTx(() -> store().upsertByKey(event("CT-KEY-S3"))).event().eventId();
        long base = store().unprocessedCount();

        store().markProcessed(id1);
        store().markFailed(id2, "요율 없음");

        assertThat(store().statusOf(id1)).isEqualTo(ProcessStatus.PROCESSED);
        assertThat(store().statusOf(id2)).isEqualTo(ProcessStatus.FAILED);
        assertThat(store().statusOf(id3)).isEqualTo(ProcessStatus.PENDING);
        // PROCESSED 1건만 빠진다 (FAILED는 재시도 대상이라 미처리로 남는다)
        assertThat(store().unprocessedCount()).isEqualTo(base - 1);
    }

    @Test
    void 실패_사유가_빈_문자열이어도_동작이_동일하다() {
        // Oracle은 VARCHAR2에 ''를 NULL로 접는다. 사유는 진단용 텍스트일 뿐이므로
        // ''와 NULL을 구분하지 않는 것이 계약이다 (설계서 §8.7 빈 문자열 시맨틱).
        long id = inTx(() -> store().upsertByKey(event("CT-KEY-EMPTY"))).event().eventId();

        store().markFailed(id, "");

        assertThat(store().statusOf(id)).isEqualTo(ProcessStatus.FAILED);
    }
}
