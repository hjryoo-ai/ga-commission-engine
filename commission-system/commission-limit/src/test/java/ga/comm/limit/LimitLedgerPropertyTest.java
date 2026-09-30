package ga.comm.limit;

import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.testing.SeededCases;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;

import static ga.comm.domain.testing.SeededCases.longIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 한도 원장 불변식 (설계서 §6.1, §8.2 — property-based, 상시 검증):
 * 어떤 전기 순서에서도 accum_paid ≤ limit_amount, accum_paid = Σ DTL.amount.
 * 시드 고정 생성기 — jqwik 대체(Phase E3-0). 정의역은 jqwik 원본과 같다.
 */
class LimitLedgerPropertyTest {

    private LimitLedger newLedger() {
        return new LimitLedger(1, new PolicyNo("P-PROP"), new AgentId("A-PROP"),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), LocalDate.of(2027, 7, 31),
                Money.won(300_000), new BigDecimal("12.00"), 1);
    }

    private static List<Long> requests(RandomGenerator r, int minSize, int maxSize, long maxValue) {
        int size = (int) longIn(r, minSize, maxSize);
        List<Long> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(longIn(r, 0, maxValue));
        }
        return list;
    }

    /** List<Long> size 1..50, 원소 0..2,000,000 */
    static Stream<Arguments> gateRequests() {
        return SeededCases.withEdges(0x5EED_E311L, SeededCases.DEFAULT_COUNT,
                List.<Object[]>of(new Object[] {List.of(0L)}, new Object[] {List.of(2_000_000L)},
                        new Object[] {Collections.nCopies(50, 2_000_000L)}, new Object[] {Collections.nCopies(50, 0L)},
                        new Object[] {List.of(300_000L, 1L)}, new Object[] {List.of(299_999L, 1L, 1L)}),
                r -> new Object[] {requests(r, 1, 50, 2_000_000)});
    }

    /** List<Long> size 1..40, 원소 0..1,500,000, deductEvery 2..5 */
    static Stream<Arguments> gateRequestsWithDeductions() {
        return SeededCases.withEdges(0x5EED_E312L, SeededCases.DEFAULT_COUNT,
                List.<Object[]>of(new Object[] {List.of(0L), 2}, new Object[] {Collections.nCopies(40, 1_500_000L), 2},
                        new Object[] {Collections.nCopies(40, 1_500_000L), 5}, new Object[] {Collections.nCopies(40, 0L), 3},
                        new Object[] {List.of(300_000L, 300_000L, 1L), 2}),
                r -> new Object[] {requests(r, 1, 40, 1_500_000), (int) longIn(r, 2, 5)});
    }

    /** excess 1..10,000,000 */
    static Stream<Arguments> excesses() {
        return SeededCases.withEdges(0x5EED_E313L, SeededCases.DEFAULT_COUNT,
                List.<Object[]>of(new Object[] {1L}, new Object[] {10_000_000L}, new Object[] {2L}),
                r -> new Object[] {longIn(r, 1, 10_000_000)});
    }

    @ParameterizedTest
    @MethodSource("gateRequests")
    void 게이트를_거친_전기는_한도를_절대_넘지_않는다(List<Long> requests) {
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

    @ParameterizedTest
    @MethodSource("gateRequestsWithDeductions")
    void 환수_차감이_섞여도_불변식은_유지된다(List<Long> requests, int deductEvery) {
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

    @ParameterizedTest
    @MethodSource("excesses")
    void 한도를_넘는_직접_전기는_예외로_차단된다(long excess) {
        LimitLedger ledger = newLedger();
        Money overLimit = ledger.limitAmount().plus(Money.won(excess));

        assertThatThrownBy(() -> ledger.post(1, overLimit))
                .isInstanceOf(LimitExceededException.class);
        // 실패한 전기는 흔적을 남기지 않는다
        assertThat(ledger.accumPaid()).isEqualTo(Money.ZERO);
        assertThat(ledger.postings()).isEmpty();
    }
}
