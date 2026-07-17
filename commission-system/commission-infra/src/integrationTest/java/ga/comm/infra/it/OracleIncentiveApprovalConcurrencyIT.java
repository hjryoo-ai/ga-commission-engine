package ga.comm.infra.it;

import ga.comm.domain.money.Money;
import ga.comm.infra.OraclePersistence;
import ga.comm.infra.store.OracleIncentiveApprovalRunner;
import ga.comm.rule.admin.IncentiveAdminStore;
import ga.comm.rule.admin.IncentiveApprovalService;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.IncentiveRule;
import ga.comm.rule.model.RateStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시책 승인 동시성 경합 (설계서 §6.6, Phase 16 선행) — 요율 {@link OracleApprovalConcurrencyIT}와
 * 동형. 같은 코드 동시 승인 2건은 앱 계층 검사를 둘 다 통과할 수 있고, 같은 개시일 충돌의 최종 심판은
 * {@code ux_incentive_active}(V102)다. 인덱스 위반은 정상 경합으로 취급되어 최신 상태 재조회 후
 * 재검증(lock-then-revalidate)으로 "코드당 겹치는 ACTIVE 0건"에 수렴한다. 인메모리·H2로 재현 불가.
 */
class OracleIncentiveApprovalConcurrencyIT {

    private static final String CD = "PUSH";
    private static final LocalDate OLD_FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate NEW_FROM = LocalDate.of(2026, 9, 1);

    private final OraclePersistence persistence = OracleTestSupport.persistence();
    private final IncentiveAdminStore adminStore = persistence.incentiveAdminStore();
    private final IncentiveApprovalService service = new IncentiveApprovalService(adminStore);
    private final OracleIncentiveApprovalRunner runner = persistence.incentiveApprovalRunner();

    @BeforeEach
    void clean() {
        OracleTestSupport.cleanAll();
    }

    private IncentiveRule register(EffectivePeriod period) {
        return persistence.inTx(() -> service.registerDraftFixed(CD, null, null, null,
                "premium >= 0", Money.won(500_000), period));
    }

    @Test
    void 결정적_인터리빙_인덱스가_최종_심판하고_패자는_재시도로_수렴한다() throws Exception {
        IncentiveRule old = register(EffectivePeriod.from(OLD_FROM));
        persistence.inTx(() -> service.approve(old.incentiveId(), "최초승인"));
        IncentiveRule draftA = register(EffectivePeriod.from(NEW_FROM));
        IncentiveRule draftB = register(EffectivePeriod.from(NEW_FROM));

        CountDownLatch aApproved = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // A: 승인 완료 후 커밋을 지연 — B의 앱 계층 검사가 A를 보지 못하는 창을 연다
            Future<?> holderA = pool.submit(() -> persistence.inTx(() -> {
                IncentiveRule approved = service.approve(draftA.incentiveId(), "승인자A");
                aApproved.countDown();
                try {
                    releaseA.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return approved;
            }));
            assertThat(aApproved.await(60, TimeUnit.SECONDS)).isTrue();

            // B: 앱 계층 검사는 통과 → 구버전 행 락 대기 → A 커밋 후 ACTIVE 전이가
            //    ux_incentive_active 위반 → 러너가 재시도로 수렴시킨다
            Future<IncentiveRule> approvalB = pool.submit(() -> runner.approve(draftB.incentiveId(), "승인자B"));
            Thread.sleep(1000);
            releaseA.countDown();
            holderA.get(60, TimeUnit.SECONDS);
            IncentiveRule finalB = approvalB.get(60, TimeUnit.SECONDS);

            // 수렴: B(나중 승인)가 ACTIVE, A는 SUPERSEDED
            assertThat(finalB.status()).isEqualTo(RateStatus.ACTIVE);
            assertThat(adminStore.findById(draftA.incentiveId()).orElseThrow().status())
                    .isEqualTo(RateStatus.SUPERSEDED);
            // 구버전은 NEW_FROM 직전으로 트리밍된 채 ACTIVE 유지
            IncentiveRule trimmedOld = adminStore.findById(old.incentiveId()).orElseThrow();
            assertThat(trimmedOld.status()).isEqualTo(RateStatus.ACTIVE);
            assertThat(trimmedOld.period().applyTo()).isEqualTo(NEW_FROM.minusDays(1));

            // 코드당 겹치는 ACTIVE 0건 — 어느 기준일에도 PUSH의 유효 ACTIVE는 정확히 1건
            assertActiveOfCd(NEW_FROM.minusDays(1), old.incentiveId());
            assertActiveOfCd(NEW_FROM, draftB.incentiveId());
            assertActiveOfCd(LocalDate.of(2026, 12, 1), draftB.incentiveId());
        } finally {
            releaseA.countDown();
            pool.shutdownNow();
        }
    }

    private void assertActiveOfCd(LocalDate baseDate, long expectedId) {
        List<IncentiveRule> active = persistence.inTx(() ->
                persistence.incentiveRepository().findActiveAt(baseDate)).stream()
                .filter(r -> r.incentiveCd().equals(CD)).toList();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).incentiveId()).isEqualTo(expectedId);
    }
}
