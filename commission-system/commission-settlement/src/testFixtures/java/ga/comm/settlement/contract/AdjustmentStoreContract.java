package ga.comm.settlement.contract;

import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.settlement.AdjustmentService;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** AdjustmentStore 계약 테스트 (설계서 §8.7) — 마감 후 정정의 익월 귀속 기록. */
public abstract class AdjustmentStoreContract {

    protected abstract AdjustmentService.AdjustmentStore store();

    /** ADJUSTMENT.target_calc_id FK를 만족하는 계산 ID. */
    protected abstract long aCalcId();

    protected <T> T inTx(Supplier<T> work) {
        return work.get();
    }

    @Test
    void 조정_레코드가_ID와_함께_생성된다() {
        long target = aCalcId();

        AdjustmentService.Adjustment created = inTx(() ->
                store().create(target, "소급 요율 정정", Money.won(-45_000), CloseYm.of("202609"), "정산팀장"));

        assertThat(created.adjId()).isPositive();
        assertThat(created.targetCalcId()).isEqualTo(target);
        assertThat(created.reason()).isEqualTo("소급 요율 정정");
        assertThat(created.amount()).isEqualTo(Money.won(-45_000));
        assertThat(created.closeYm()).isEqualTo(CloseYm.of("202609"));
        assertThat(created.approvedBy()).isEqualTo("정산팀장");
    }

    @Test
    void 대상_calc_없는_수기_조정도_허용된다() {
        AdjustmentService.Adjustment created = inTx(() ->
                store().create(null, "수기 조정", Money.won(10_000), CloseYm.of("202610"), "정산팀장"));

        assertThat(created.adjId()).isPositive();
        assertThat(created.targetCalcId()).isNull();
    }
}
