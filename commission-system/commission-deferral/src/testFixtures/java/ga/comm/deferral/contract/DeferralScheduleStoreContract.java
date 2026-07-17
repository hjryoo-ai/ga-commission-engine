package ga.comm.deferral.contract;

import ga.comm.deferral.DeferralScheduleStore;
import ga.comm.deferral.ScheduleEntry;
import ga.comm.deferral.ScheduleStatus;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DeferralScheduleStore 계약 테스트 (설계서 §8.7) — 스케줄 생성·도래 조회·상태 전이 왕복. */
public abstract class DeferralScheduleStoreContract {

    protected abstract DeferralScheduleStore store();

    /** DEFERRAL_SCHEDULE.source_calc_id FK를 만족하는 계산 ID. */
    protected abstract long aCalcId();

    protected <T> T inTx(Supplier<T> work) {
        return work.get();
    }

    private ScheduleEntry entry(long sourceCalcId, String policy, String dueYm, long amount) {
        return new ScheduleEntry(null, sourceCalcId, new PolicyNo(policy), new AgentId("A-1001"),
                CloseYm.of(dueYm), Money.won(amount), ScheduleEntry.CONDITION_POLICY_INFORCE,
                ScheduleStatus.SCHEDULED, 4001L, null);
    }

    @Test
    void createAll은_ID를_부여하고_모든_필드를_보존한다() {
        long source = aCalcId();
        List<ScheduleEntry> created = inTx(() -> store().createAll(List.of(
                entry(source, "POL-DC-1", "202807", 126_000),
                entry(source, "POL-DC-1", "202907", 126_000))));

        assertThat(created).allSatisfy(e -> assertThat(e.scheduleId()).isNotNull());
        List<ScheduleEntry> reloaded = inTx(() -> store().findBySourceCalcId(source));
        assertThat(reloaded).containsExactlyInAnyOrderElementsOf(created);
    }

    @Test
    void findDue는_해당_월의_SCHEDULED만_반환한다() {
        long source = aCalcId();
        List<ScheduleEntry> created = inTx(() -> store().createAll(List.of(
                entry(source, "POL-DC-2", "202807", 100_000),
                entry(source, "POL-DC-2", "202807", 50_000),
                entry(source, "POL-DC-2", "202808", 70_000))));
        // 하나는 이미 취소됨
        inTx(() -> {
            store().replace(created.get(1).cancelled());
            return null;
        });

        List<ScheduleEntry> due = inTx(() -> store().findDue(CloseYm.of("202807")));

        assertThat(due).hasSize(1);
        assertThat(due.get(0).scheduleId()).isEqualTo(created.get(0).scheduleId());
    }

    @Test
    void RELEASED_전이는_생성된_calcId와_함께_왕복_보존된다() {
        long source = aCalcId();
        long releasedCalc = aCalcId();
        ScheduleEntry created = inTx(() -> store().createAll(
                List.of(entry(source, "POL-DC-3", "202807", 126_000)))).get(0);

        inTx(() -> {
            store().replace(created.released(releasedCalc));
            return null;
        });

        ScheduleEntry reloaded = inTx(() -> store().findBySourceCalcId(source)).get(0);
        assertThat(reloaded.status()).isEqualTo(ScheduleStatus.RELEASED);
        assertThat(reloaded.releasedCalcId()).isEqualTo(releasedCalc);
        assertThat(reloaded.amount()).isEqualTo(Money.won(126_000));
    }

    @Test
    void HELD와_CANCELLED_전이가_왕복_보존된다() {
        long source = aCalcId();
        List<ScheduleEntry> created = inTx(() -> store().createAll(List.of(
                entry(source, "POL-DC-4", "202807", 100_000),
                entry(source, "POL-DC-4", "202808", 100_000))));

        inTx(() -> {
            store().replace(created.get(0).held());
            store().replace(created.get(1).cancelled());
            return null;
        });

        List<ScheduleEntry> reloaded = inTx(() -> store().findBySourceCalcId(source));
        assertThat(reloaded).extracting(ScheduleEntry::status)
                .containsExactlyInAnyOrder(ScheduleStatus.HELD, ScheduleStatus.CANCELLED);
    }

    @Test
    void findByPolicyAndAgent는_해당_계약_설계사만_반환한다() {
        long source = aCalcId();
        inTx(() -> store().createAll(List.of(entry(source, "POL-DC-5", "202807", 100_000))));

        assertThat(inTx(() -> store().findByPolicyAndAgent(new PolicyNo("POL-DC-5"), new AgentId("A-1001"))))
                .hasSize(1);
        assertThat(inTx(() -> store().findByPolicyAndAgent(new PolicyNo("POL-DC-5"), new AgentId("A-9999"))))
                .isEmpty();
    }

    @Test
    void 존재하지_않는_스케줄의_replace는_예외다() {
        ScheduleEntry ghost = entry(aCalcId(), "POL-DC-6", "202807", 100_000)
                .withId(999_999_999L).held();

        assertThatThrownBy(() -> inTx(() -> {
            store().replace(ghost);
            return null;
        })).isInstanceOf(IllegalStateException.class);
    }
}
