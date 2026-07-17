package ga.comm.infra.it;

import ga.comm.calc.CalcResultListener;
import ga.comm.calc.CalculationPipeline;
import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.StepConfig;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.calc.recipient.AffiliationBasis;
import ga.comm.calc.recipient.RecipientResolver;
import ga.comm.calc.step.BaseCommissionStep;
import ga.comm.calc.step.IncentiveStep;
import ga.comm.calc.step.OverrideCommissionStep;
import ga.comm.calc.step.PayoutRateStep;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CloseYmResolver;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.infra.OraclePersistence;
import ga.comm.limit.LimitGateStep;
import ga.comm.limit.LimitLedger;
import ga.comm.limit.LimitLedgerPoster;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.rule.model.EffectivePeriod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static ga.comm.calc.fixture.EventFixtures.AGENT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §8.6 동시성 경합 테스트 — 인메모리·H2로 대체 불가, 실제 Oracle 락으로만 증명된다.
 *
 * <p>파이프라인은 실 배선과 동일: Oracle Store + LimitGateStep(50) + 저장 후 훅(LimitLedgerPoster),
 * 전체를 TransactionTemplate(§4.2 [3.5]~[5.5])로 감싼다. 룰/조직은 읽기 전용이라 인메모리 픽스처를 쓴다.
 */
class OracleLimitRaceIT {

    private static final LocalDate STEPS_FROM = LocalDate.of(2026, 1, 1);

    private final OraclePersistence persistence = OracleTestSupport.persistence();

    @BeforeEach
    void clean() {
        OracleTestSupport.cleanAll();
    }

    private CommissionCalculator calculator(CalcResultListener... extraListeners) {
        List<StepConfig> steps = List.of(
                new StepConfig(new BaseCommissionStep(), EffectivePeriod.from(STEPS_FROM), 10),
                new StepConfig(new PayoutRateStep(), EffectivePeriod.from(STEPS_FROM), 20),
                new StepConfig(new IncentiveStep(), EffectivePeriod.from(STEPS_FROM), 30),
                new StepConfig(new OverrideCommissionStep(), EffectivePeriod.from(STEPS_FROM), 40),
                new StepConfig(new LimitGateStep(persistence.limitLedgerStore()),
                        EffectivePeriod.from(STEPS_FROM), 50));
        List<CalcResultListener> listeners = new ArrayList<>();
        listeners.add(new LimitLedgerPoster(persistence.limitLedgerStore()));
        listeners.addAll(List.of(extraListeners));
        return new CommissionCalculator(
                RuleFixtures.standardRules(),
                new RecipientResolver(CalcTestHarness.standardDirectory(), AffiliationBasis.EVENT_DATE),
                new CalculationPipeline(steps),
                persistence.policyEventStore(),
                persistence.commCalcStore(),
                CloseYmResolver.byEventDate(),
                listeners);
    }

    private List<Future<Object>> raceTwo(Callable<Object> first, Callable<Object> second)
            throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = List.of(
                    pool.submit(() -> {
                        barrier.await(30, TimeUnit.SECONDS);
                        return first.call();
                    }),
                    pool.submit(() -> {
                        barrier.await(30, TimeUnit.SECONDS);
                        return second.call();
                    }));
            for (Future<Object> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
            return futures;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 잔여_한도가_1건분만_남으면_두_번째_트랜잭션은_갱신된_누적을_보고_삭감된다() throws Exception {
        CommissionCalculator calculator = calculator();
        PolicyNo policy = new PolicyNo("POL-RACE-1");

        // 선행 신계약: 2,100,000 × 지급률 90% = 1,890,000 전기 → 잔여 1,710,000
        persistence.inTx(() -> calculator.process(
                EventFixtures.newContract(policy, EventFixtures.CONTRACT_DATE, Money.won(300_000), Map.of())));

        // 회차입금 + 시책 1,000,000 두 건을 실제 경합 — 합계 2,081,000 > 잔여 1,710,000
        PolicyEvent payment2 = EventFixtures.payment(policy, EventFixtures.CONTRACT_DATE, 2,
                LocalDate.of(2026, 9, 1), Money.won(300_000),
                Map.of(IncentiveStep.ATTR_INCENTIVE_AMOUNT, "1000000"));
        PolicyEvent payment3 = EventFixtures.payment(policy, EventFixtures.CONTRACT_DATE, 3,
                LocalDate.of(2026, 10, 1), Money.won(300_000),
                Map.of(IncentiveStep.ATTR_INCENTIVE_AMOUNT, "1000000"));

        raceTwo(() -> persistence.inTx(() -> calculator.process(payment2)),
                () -> persistence.inTx(() -> calculator.process(payment3)));

        // 불변식: 멀티스레드에서도 accum_paid ≤ limit_amount — 정확히 한도에서 멈춘다
        LimitLedger ledger = persistence.inTx(() ->
                persistence.limitLedgerStore().find(policy, AGENT_A).orElseThrow());
        assertThat(ledger.accumPaid()).isEqualTo(Money.won(3_600_000));
        assertThat(ledger.accumPaid()).isEqualTo(ledger.limitAmount());
        assertThat(ledger.invariantHolds()).isTrue();

        // 뒤진 트랜잭션 정확히 1건만 삭감(이연)됐다: 시책 1,000,000 중 629,000만 지급
        List<CommCalcRecord> cut = persistence.inTx(() ->
                        persistence.commCalcStore().findByPolicyAndRecipient(policy, AGENT_A.value()))
                .stream().filter(r -> r.limitCutAmt().isPositive()).toList();
        assertThat(cut).hasSize(1);
        assertThat(cut.get(0).limitCutAmt()).isEqualTo(Money.won(371_000));
        assertThat(cut.get(0).calcAmount()).isEqualTo(Money.won(629_000));
    }

    @Test
    void 신규_원장_동시_생성은_uq_limit_재시도로_직렬화된다() throws Exception {
        CommissionCalculator calculator = calculator();
        PolicyNo policy = new PolicyNo("POL-RACE-NEW");

        PolicyEvent payment2 = EventFixtures.payment(policy, EventFixtures.CONTRACT_DATE, 2,
                LocalDate.of(2026, 9, 1), Money.won(300_000), Map.of());
        PolicyEvent payment3 = EventFixtures.payment(policy, EventFixtures.CONTRACT_DATE, 3,
                LocalDate.of(2026, 10, 1), Money.won(300_000), Map.of());

        raceTwo(() -> persistence.inTx(() -> calculator.process(payment2)),
                () -> persistence.inTx(() -> calculator.process(payment3)));

        // 원장은 단 1개, 두 건의 전기가 모두 반영됐다 (각 45,000 × 90% = 40,500)
        List<LimitLedger> ledgers = persistence.inTx(() -> persistence.limitLedgerStore().findAll())
                .stream().filter(l -> l.policyNo().equals(policy)).toList();
        assertThat(ledgers).hasSize(1);
        assertThat(ledgers.get(0).accumPaid()).isEqualTo(Money.won(81_000));
        assertThat(ledgers.get(0).postings()).hasSize(2);
        assertThat(ledgers.get(0).invariantHolds()).isTrue();
    }

    @Test
    void 저장_후_훅까지_하나의_트랜잭션이다_훅_실패_시_계산_전기_이벤트가_전부_롤백된다() {
        CalcResultListener bomb = (ctx, lines) -> {
            throw new IllegalStateException("훅 실패 유도");
        };
        CommissionCalculator calculator = calculator(bomb);
        PolicyNo policy = new PolicyNo("POL-ATOMIC");
        PolicyEvent event = EventFixtures.newContract(policy, EventFixtures.CONTRACT_DATE,
                Money.won(300_000), Map.of());

        assertThatThrownBy(() -> persistence.inTx(() -> calculator.process(event)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("훅 실패 유도");

        // §4.2 [3.5]~[5.5] 원자성: COMM_CALC·LIMIT_LEDGER(DTL)·POLICY_EVENT 전부 롤백
        assertThat(persistence.inTx(() ->
                persistence.commCalcStore().findByPolicyAndRecipient(policy, AGENT_A.value()))).isEmpty();
        assertThat(persistence.inTx(() ->
                persistence.limitLedgerStore().find(policy, AGENT_A))).isEmpty();
        assertThat(persistence.inTx(() ->
                persistence.policyEventStore().upsertByKey(event)).duplicate())
                .as("이벤트 행도 롤백되어 재수신이 신규로 처리된다 (재시도 가능)")
                .isFalse();
    }
}
