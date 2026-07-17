package ga.comm.infra.it;

import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.infra.OraclePersistence;
import ga.comm.limit.LimitLedgerStore;
import ga.comm.rule.model.ChannelType;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.LimitRule;
import ga.comm.rule.model.OverLimitAction;
import ga.comm.settlement.SettleCloseStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Oracle 고유 시맨틱 검증 (§8.7):
 * ① FOR UPDATE 대기 — 원장 락은 커밋까지 유지되고 경쟁 트랜잭션은 블로킹된다 (§6.1.6).
 * ② 빈 문자열 = NULL 접힘(VARCHAR2) — H2가 재현하지 못하는 동작의 명시적 고정.
 */
class OracleLockSemanticsIT {

    private static final PolicyNo POLICY = new PolicyNo("POL-LOCK-1");
    private static final AgentId AGENT = new AgentId("A-1001");

    private final OraclePersistence persistence = OracleTestSupport.persistence();

    @BeforeEach
    void clean() {
        OracleTestSupport.cleanAll();
    }

    private static LimitRule rule() {
        return new LimitRule(9001L, ChannelType.GA_TO_AGENT, new BigDecimal("12.00"), 12,
                OverLimitAction.DEFER_AFTER_FY, true, EffectivePeriod.from(LocalDate.of(2026, 7, 1)));
    }

    @Test
    void 원장_FOR_UPDATE는_보유_트랜잭션이_커밋할_때까지_경쟁자를_대기시킨다() throws Exception {
        LimitLedgerStore store = persistence.limitLedgerStore();
        persistence.inTx(() -> store.getOrCreate(POLICY, AGENT, LocalDate.of(2026, 8, 1),
                Money.won(300_000), rule()));

        long holdMillis = 1500;
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> persistence.inTx(() -> {
                store.find(POLICY, AGENT); // SELECT FOR UPDATE — 락 획득
                lockHeld.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));

            assertThat(lockHeld.await(60, TimeUnit.SECONDS)).isTrue();
            Future<Long> waiter = pool.submit(() -> {
                long start = System.nanoTime();
                persistence.inTx(() -> store.find(POLICY, AGENT)); // 같은 행 잠금 시도 → 대기
                return (System.nanoTime() - start) / 1_000_000;
            });

            Thread.sleep(holdMillis); // waiter를 락 대기 상태로 붙잡아 둔다
            release.countDown();
            holder.get(60, TimeUnit.SECONDS);

            long waitedMillis = waiter.get(60, TimeUnit.SECONDS);
            assertThat(waitedMillis)
                    .as("경쟁 트랜잭션은 락 보유자가 커밋할 때까지 블로킹되어야 한다")
                    .isGreaterThanOrEqualTo(holdMillis - 300);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void VARCHAR2의_빈_문자열은_NULL로_접힌다_동작은_동일해야_한다() throws Exception {
        // SETTLE_CLOSE.closed_by = ''
        SettleCloseStore closeStore = persistence.settleCloseStore();
        persistence.inTx(() -> {
            closeStore.transition(CloseYm.of("202608"), SettleCloseStore.CloseState.CLOSING, "");
            return null;
        });
        assertThat(persistence.inTx(() -> closeStore.stateOf(CloseYm.of("202608"))))
                .isEqualTo(SettleCloseStore.CloseState.CLOSING);
        assertThat(selectString("SELECT closed_by FROM SETTLE_CLOSE WHERE close_ym = '202608'"))
                .as("Oracle은 ''를 NULL로 저장한다 — closed_by는 진단 텍스트라 구분하지 않는 것이 계약")
                .isNull();

        // POLICY_EVENT.fail_reason = ''
        long eventId = OracleTestSupport.newEventId();
        persistence.inTx(() -> {
            persistence.policyEventStore().markFailed(eventId, "");
            return null;
        });
        assertThat(selectString("SELECT fail_reason FROM POLICY_EVENT WHERE event_id = " + eventId))
                .isNull();
    }

    private String selectString(String sql) throws Exception {
        try (Connection connection = OracleTestSupport.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }
}
