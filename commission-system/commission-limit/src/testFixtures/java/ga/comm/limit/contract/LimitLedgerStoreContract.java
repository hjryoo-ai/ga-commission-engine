package ga.comm.limit.contract;

import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.limit.LimitLedger;
import ga.comm.limit.LimitLedgerStore;
import ga.comm.rule.model.ChannelType;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.LimitRule;
import ga.comm.rule.model.OverLimitAction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LimitLedgerStore 계약 테스트 (설계서 §8.7) — 원장 개설·전기 왕복·posting 순서 보존
 * (posting_seq 시맨틱, 부록 B-8)의 동작 동등성을 증명한다.
 *
 * <p>FOR UPDATE 대기·경합 직렬화는 인메모리로 재현할 수 없으므로 이 계약에 없다 —
 * Oracle 어댑터 전용 동시성 테스트(§8.6)가 별도로 증명한다.
 */
public abstract class LimitLedgerStoreContract {

    protected static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 8, 1);

    protected abstract LimitLedgerStore store();

    /** LIMIT_LEDGER_DTL.calc_id FK를 만족하는 계산 ID. DB 구현은 실제 COMM_CALC 행을 만든다. */
    protected abstract long aCalcId();

    protected <T> T inTx(Supplier<T> work) {
        return work.get();
    }

    protected void inTx(Runnable work) {
        inTx(() -> {
            work.run();
            return null;
        });
    }

    protected LimitRule rule() {
        return new LimitRule(9001L, ChannelType.GA_TO_AGENT, new BigDecimal("12.00"), 12,
                OverLimitAction.DEFER_AFTER_FY, true, EffectivePeriod.from(LocalDate.of(2026, 7, 1)));
    }

    private LimitLedger open(String policy, String agent) {
        return store().getOrCreate(new PolicyNo(policy), new AgentId(agent),
                CONTRACT_DATE, Money.won(300_000), rule());
    }

    @Test
    void 개설_시_초년도_윈도우와_한도액이_룰대로_계산된다() {
        LimitLedger ledger = inTx(() -> open("POL-LC-1", "A-1001"));

        assertThat(ledger.fyStart()).isEqualTo(CONTRACT_DATE);
        assertThat(ledger.fyEnd()).isEqualTo(CONTRACT_DATE.plusMonths(12).minusDays(1));
        assertThat(ledger.limitAmount()).isEqualTo(Money.won(3_600_000));
        assertThat(ledger.accumPaid()).isEqualTo(Money.ZERO);
    }

    @Test
    void getOrCreate는_같은_키에_같은_원장을_돌려준다() {
        LimitLedger first = inTx(() -> open("POL-LC-2", "A-1001"));
        LimitLedger second = inTx(() -> open("POL-LC-2", "A-1001"));

        assertThat(second.ledgerId()).isEqualTo(first.ledgerId());
        assertThat(store().findAll().stream()
                .filter(l -> l.policyNo().equals(new PolicyNo("POL-LC-2"))).count()).isEqualTo(1);
    }

    @Test
    void 전기_후_재조회하면_누적과_전기_내역이_순서대로_보존된다() {
        long calc1 = aCalcId();
        long calc2 = aCalcId();
        long calc3 = aCalcId();

        inTx(() -> {
            LimitLedger ledger = open("POL-LC-3", "A-1001");
            ledger.post(calc1, Money.won(2_800_000));
            ledger.post(calc2, Money.won(800_000));
            ledger.post(calc3, Money.won(-500_000)); // 환수 차감
            store().save(ledger);
        });

        LimitLedger reloaded = inTx(() ->
                store().find(new PolicyNo("POL-LC-3"), new AgentId("A-1001")).orElseThrow());

        assertThat(reloaded.accumPaid()).isEqualTo(Money.won(3_100_000));
        // 전기 순서 보존 — DB 구현은 posting_seq 정렬로 이 순서를 재현해야 한다 (부록 B-8)
        assertThat(reloaded.postings()).containsExactly(
                new LimitLedger.Posting(calc1, Money.won(2_800_000)),
                new LimitLedger.Posting(calc2, Money.won(800_000)),
                new LimitLedger.Posting(calc3, Money.won(-500_000)));
        assertThat(reloaded.invariantHolds()).isTrue();
    }

    @Test
    void 여러_번에_나눠_전기해도_증분만_저장된다() {
        long calc1 = aCalcId();
        long calc2 = aCalcId();

        inTx(() -> {
            LimitLedger ledger = open("POL-LC-4", "A-1001");
            ledger.post(calc1, Money.won(1_000_000));
            store().save(ledger);
        });
        inTx(() -> {
            LimitLedger ledger = store().find(new PolicyNo("POL-LC-4"), new AgentId("A-1001")).orElseThrow();
            ledger.post(calc2, Money.won(200_000));
            store().save(ledger);
        });

        LimitLedger reloaded = inTx(() ->
                store().find(new PolicyNo("POL-LC-4"), new AgentId("A-1001")).orElseThrow());
        assertThat(reloaded.postings()).hasSize(2);
        assertThat(reloaded.accumPaid()).isEqualTo(Money.won(1_200_000));
    }

    @Test
    void 감액_재산정으로_한도가_누적_아래로_내려간_상태도_왕복_보존된다() {
        long calc1 = aCalcId();

        inTx(() -> {
            LimitLedger ledger = open("POL-LC-5", "A-1001");
            ledger.post(calc1, Money.won(3_000_000));
            ledger.reprice(Money.won(200_000)); // 신한도 2,400,000 < 기지급 3,000,000
            store().save(ledger);
        });

        LimitLedger reloaded = inTx(() ->
                store().find(new PolicyNo("POL-LC-5"), new AgentId("A-1001")).orElseThrow());

        assertThat(reloaded.monthlyPremium()).isEqualTo(Money.won(200_000));
        assertThat(reloaded.limitAmount()).isEqualTo(Money.won(2_400_000));
        assertThat(reloaded.accumPaid()).isEqualTo(Money.won(3_000_000)); // 기지급 유지 (§6.1.8)
        assertThat(reloaded.invariantHolds()).isTrue();
    }

    @Test
    void 없는_원장_find는_비어있다() {
        assertThat(inTx(() -> store().find(new PolicyNo("POL-NONE"), new AgentId("A-0000"))))
                .isEmpty();
    }

    @Test
    void findAll은_모든_원장을_반환한다() {
        inTx(() -> open("POL-LC-6", "A-1001"));
        inTx(() -> open("POL-LC-6", "A-2002"));

        assertThat(inTx(() -> store().findAll()).stream()
                .filter(l -> l.policyNo().equals(new PolicyNo("POL-LC-6")))
                .count()).isEqualTo(2);
    }
}
