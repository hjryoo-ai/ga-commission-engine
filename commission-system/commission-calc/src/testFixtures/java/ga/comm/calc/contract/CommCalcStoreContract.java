package ga.comm.calc.contract;

import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;
import ga.comm.domain.type.RecipientType;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CommCalcStore 계약 테스트 (설계서 §8.7) — 불변 원장 규약(insert + 상태 전이만)의
 * 동작 동등성을 인메모리 레퍼런스와 Oracle 어댑터에서 증명한다.
 */
public abstract class CommCalcStoreContract {

    protected abstract CommCalcStore store();

    /** COMM_CALC.event_id FK를 만족하는 이벤트 ID. DB 구현은 실제 POLICY_EVENT 행을 만든다. */
    protected abstract long anEventId();

    protected <T> T inTx(Supplier<T> work) {
        return work.get();
    }

    private CommCalcRecord record(long eventId) {
        return new CommCalcRecord(null, eventId, new PolicyNo("POL-CT-1"), RecipientType.AGENT,
                "A-1001", CommTypeCode.FY_COMM, Money.won(2_100_000), Rate.percent("90"),
                Money.won(1_890_000), Money.ZERO, CloseYm.of("202608"), CalcStatus.CALCULATED,
                null, "{\"commRate\":[41]}", "{\"steps\":[]}");
    }

    @Test
    void insert는_calcId를_부여하고_모든_필드를_보존한다() {
        long eventId = anEventId();
        CommCalcRecord saved = inTx(() -> store().insert(record(eventId)));

        assertThat(saved.calcId()).isNotNull();
        CommCalcRecord reloaded = store().findById(saved.calcId()).orElseThrow();
        assertThat(reloaded).isEqualTo(saved);
    }

    @Test
    void 이미_calcId가_부여된_레코드의_insert는_거부된다() {
        long eventId = anEventId();
        CommCalcRecord saved = inTx(() -> store().insert(record(eventId)));

        assertThatThrownBy(() -> inTx(() -> store().insert(saved)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 음수_환수_레코드와_reversal_참조가_왕복_보존된다() {
        long eventId = anEventId();
        CommCalcRecord original = inTx(() -> store().insert(record(eventId)));
        CommCalcRecord reversal = new CommCalcRecord(null, eventId, original.policyNo(),
                RecipientType.AGENT, "A-1001", CommTypeCode.FY_COMM, original.baseAmount(),
                null, original.calcAmount().negate(), Money.ZERO, CloseYm.of("202609"),
                CalcStatus.CALCULATED, original.calcId(), original.ruleVersions(), "{}");

        CommCalcRecord saved = inTx(() -> store().insert(reversal));

        CommCalcRecord reloaded = store().findById(saved.calcId()).orElseThrow();
        assertThat(reloaded.calcAmount()).isEqualTo(Money.won(-1_890_000));
        assertThat(reloaded.appliedRate()).isNull();
        assertThat(reloaded.reversalOf()).isEqualTo(original.calcId());
    }

    @Test
    void 허용된_상태_전이만_가능하다() {
        long eventId = anEventId();
        CommCalcRecord saved = inTx(() -> store().insert(record(eventId)));

        assertThat(inTx(() -> store().transition(saved.calcId(), CalcStatus.CONFIRMED)).status())
                .isEqualTo(CalcStatus.CONFIRMED);
        assertThat(inTx(() -> store().transition(saved.calcId(), CalcStatus.PAID)).status())
                .isEqualTo(CalcStatus.PAID);
        assertThat(inTx(() -> store().transition(saved.calcId(), CalcStatus.REVERSED)).status())
                .isEqualTo(CalcStatus.REVERSED);
    }

    @Test
    void 허용되지_않는_전이는_예외이고_상태가_바뀌지_않는다() {
        long eventId = anEventId();
        CommCalcRecord saved = inTx(() -> store().insert(record(eventId)));
        inTx(() -> store().transition(saved.calcId(), CalcStatus.CONFIRMED));

        assertThatThrownBy(() -> inTx(() -> store().transition(saved.calcId(), CalcStatus.CALCULATED)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store().findById(saved.calcId()).orElseThrow().status())
                .isEqualTo(CalcStatus.CONFIRMED);
    }

    @Test
    void REVERSED는_종결_상태다() {
        long eventId = anEventId();
        CommCalcRecord saved = inTx(() -> store().insert(record(eventId)));
        inTx(() -> store().transition(saved.calcId(), CalcStatus.REVERSED));

        assertThatThrownBy(() -> inTx(() -> store().transition(saved.calcId(), CalcStatus.CONFIRMED)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 존재하지_않는_calcId_전이는_예외다() {
        assertThatThrownBy(() -> inTx(() -> store().transition(999_999_999L, CalcStatus.CONFIRMED)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 조회는_이벤트_귀속월_수급자별로_필터링된다() {
        long event1 = anEventId();
        long event2 = anEventId();
        CommCalcRecord r1 = inTx(() -> store().insert(record(event1)));
        CommCalcRecord other = new CommCalcRecord(null, event2, new PolicyNo("POL-CT-2"),
                RecipientType.ORG, "TEAM-01", CommTypeCode.OVERRIDE, Money.won(100_000),
                Rate.percent("5"), Money.won(5_000), Money.ZERO, CloseYm.of("202609"),
                CalcStatus.CALCULATED, null, null, null);
        CommCalcRecord r2 = inTx(() -> store().insert(other));

        assertThat(store().findByEventId(event1)).containsExactly(r1);
        assertThat(store().findByCloseYm(CloseYm.of("202609"))).containsExactly(r2);
        assertThat(store().findByPolicyAndRecipient(new PolicyNo("POL-CT-1"), "A-1001"))
                .containsExactly(r1);
        assertThat(store().findByPolicyAndRecipient(new PolicyNo("POL-CT-1"), "TEAM-01")).isEmpty();
    }
}
