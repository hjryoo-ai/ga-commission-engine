package ga.comm.limit;

import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 한도 원장 불변식 (설계서 §6.1, §8.2 — property-based, 상시 검증):
 * 어떤 전기 순서에서도 accum_paid ≤ limit_amount, accum_paid = Σ DTL.amount.
 */
class LimitLedgerPropertyTest {

    private LimitLedger newLedger() {
        return new LimitLedger(1, new PolicyNo("P-PROP"), new AgentId("A-PROP"),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), LocalDate.of(2027, 7, 31),
                Money.won(300_000), new BigDecimal("12.00"), 1);
    }

    @Property
    void 게이트를_거친_전기는_한도를_절대_넘지_않는다(
            @ForAll @Size(min = 1, max = 50) List<@LongRange(min = 0, max = 2_000_000) Long> requests) {
        LimitLedger ledger = newLedger();
        long calcId = 0;

        for (long requested : requests) {
            // 게이트 로직: payable = min(요청액, 잔여 한도)
            Money available = ledger.limitAmount().minus(ledger.accumPaid()).max(Money.ZERO);
            Money payable = Money.won(requested).min(available);
            if (payable.isPositive()) {
                ledger.post(++calcId, payable);
            }

            assertThat(ledger.accumPaid().isLessThanOrEqual(ledger.limitAmount())).isTrue();
            assertThat(ledger.invariantHolds()).isTrue();
        }
    }

    @Property
    void 환수_차감이_섞여도_불변식은_유지된다(
            @ForAll @Size(min = 1, max = 40) List<@LongRange(min = 0, max = 1_500_000) Long> requests,
            @ForAll @IntRange(min = 2, max = 5) int deductEvery) {
        LimitLedger ledger = newLedger();
        long calcId = 0;
        int i = 0;

        for (long requested : requests) {
            Money available = ledger.limitAmount().minus(ledger.accumPaid()).max(Money.ZERO);
            Money payable = Money.won(requested).min(available);
            if (payable.isPositive()) {
                ledger.post(++calcId, payable);
            }
            if (++i % deductEvery == 0 && ledger.accumPaid().isPositive()) {
                // 환수: 기지급의 일부 차감 (accum을 넘지 않는 금액)
                Money deduct = ledger.accumPaid().min(Money.won(requested / 2 + 1));
                ledger.post(++calcId, deduct.negate());
            }

            assertThat(ledger.accumPaid().isLessThanOrEqual(ledger.limitAmount())).isTrue();
            assertThat(ledger.accumPaid().isNegative()).isFalse();
            assertThat(ledger.invariantHolds()).isTrue();
        }
    }

    @Property
    void 한도를_넘는_직접_전기는_예외로_차단된다(
            @ForAll @LongRange(min = 1, max = 10_000_000) long excess) {
        LimitLedger ledger = newLedger();
        Money overLimit = ledger.limitAmount().plus(Money.won(excess));

        assertThatThrownBy(() -> ledger.post(1, overLimit))
                .isInstanceOf(LimitExceededException.class);
        // 실패한 전기는 흔적을 남기지 않는다
        assertThat(ledger.accumPaid()).isEqualTo(Money.ZERO);
        assertThat(ledger.postings()).isEmpty();
    }
}
