package ga.comm.clawback.contract;

import ga.comm.clawback.ClawbackReceivable;
import ga.comm.clawback.ClawbackReceivableStore;
import ga.comm.clawback.ReceivableStatus;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.money.Money;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** ClawbackReceivableStore 계약 테스트 (설계서 §8.7) — 채권 생성·상계 상태 왕복·오래된 순 조회. */
public abstract class ClawbackReceivableStoreContract {

    protected static final AgentId AGENT = new AgentId("A-1001");

    protected abstract ClawbackReceivableStore store();

    /** CLAWBACK_OFFSET_HIST.calc_id FK를 만족하는 계산 ID. */
    protected abstract long aCalcId();

    protected <T> T inTx(Supplier<T> work) {
        return work.get();
    }

    @Test
    void 생성된_채권은_OPEN이고_잔액은_원금과_같다() {
        ClawbackReceivable created = inTx(() -> store().create(AGENT, null, Money.won(500_000)));

        assertThat(created.status()).isEqualTo(ReceivableStatus.OPEN);
        assertThat(created.remaining()).isEqualTo(Money.won(500_000));

        List<ClawbackReceivable> reloaded = inTx(() -> store().findOffsettable(AGENT));
        assertThat(reloaded).hasSize(1);
        assertThat(reloaded.get(0).receivableId()).isEqualTo(created.receivableId());
        assertThat(reloaded.get(0).amount()).isEqualTo(Money.won(500_000));
    }

    @Test
    void 상계_가능_채권은_오래된_것부터_반환된다() {
        ClawbackReceivable first = inTx(() -> store().create(AGENT, null, Money.won(100_000)));
        ClawbackReceivable second = inTx(() -> store().create(AGENT, null, Money.won(200_000)));

        List<ClawbackReceivable> offsettable = inTx(() -> store().findOffsettable(AGENT));

        assertThat(offsettable).extracting(ClawbackReceivable::receivableId)
                .containsExactly(first.receivableId(), second.receivableId());
    }

    @Test
    void 부분_상계_상태가_왕복_보존된다() {
        long payingCalc = aCalcId();
        inTx(() -> {
            ClawbackReceivable receivable = store().create(AGENT, null, Money.won(300_000));
            Money applied = receivable.offset(Money.won(120_000));
            store().save(receivable);
            store().recordOffset(receivable.receivableId(), payingCalc, applied);
            return null;
        });

        ClawbackReceivable reloaded = inTx(() -> store().findOffsettable(AGENT)).get(0);
        assertThat(reloaded.status()).isEqualTo(ReceivableStatus.OFFSET);
        assertThat(reloaded.remaining()).isEqualTo(Money.won(180_000));
        assertThat(reloaded.amount()).isEqualTo(Money.won(300_000));
    }

    @Test
    void 전액_상계된_채권은_상계_대상에서_빠진다() {
        inTx(() -> {
            ClawbackReceivable receivable = store().create(AGENT, null, Money.won(100_000));
            receivable.offset(Money.won(100_000));
            store().save(receivable);
            return null;
        });

        assertThat(inTx(() -> store().findOffsettable(AGENT))).isEmpty();
    }

    @Test
    void 대손_처리된_채권은_상계_대상에서_빠진다() {
        inTx(() -> {
            ClawbackReceivable receivable = store().create(AGENT, null, Money.won(100_000));
            receivable.writeOff();
            store().save(receivable);
            return null;
        });

        assertThat(inTx(() -> store().findOffsettable(AGENT))).isEmpty();
    }

    @Test
    void 원본_calc_참조가_왕복_보존된다() {
        long origin = aCalcId();
        inTx(() -> store().create(AGENT, origin, Money.won(50_000)));

        assertThat(inTx(() -> store().findOffsettable(AGENT)).get(0).originCalcId())
                .isEqualTo(origin);
    }

    @Test
    void 다른_설계사의_채권은_조회되지_않는다() {
        inTx(() -> store().create(AGENT, null, Money.won(100_000)));

        assertThat(inTx(() -> store().findOffsettable(new AgentId("A-9999")))).isEmpty();
    }
}
