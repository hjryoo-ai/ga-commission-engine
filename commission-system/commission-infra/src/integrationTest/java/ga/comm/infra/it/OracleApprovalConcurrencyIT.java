package ga.comm.infra.it;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Rate;
import ga.comm.domain.type.Direction;
import ga.comm.infra.OraclePersistence;
import ga.comm.infra.store.OracleRateApprovalRunner;
import ga.comm.rule.admin.CommRateAdminStore;
import ga.comm.rule.admin.RateApprovalService;
import ga.comm.rule.admin.RuleChangeEntry;
import ga.comm.rule.model.CommRateRule;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.RateStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 승인 동시성 경합 (설계서 §6.6 v1.1.2) — 같은 키 동시 승인 2건은 앱 계층 검사를 둘 다
 * 통과할 수 있고, 최종 심판은 ux_comm_rate_active(V100)다. 인덱스 위반은 정상 경합으로
 * 취급되어 최신 상태 재조회 후 재검증(재시도)으로 "겹치는 ACTIVE 0건"에 수렴해야 한다.
 * 인메모리·H2로 재현 불가 — 실제 Oracle에서만 증명된다.
 */
class OracleApprovalConcurrencyIT {

    private static final LocalDate OLD_FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate NEW_FROM = LocalDate.of(2026, 9, 1);

    private final OraclePersistence persistence = OracleTestSupport.persistence();
    private final CommRateAdminStore adminStore = persistence.commRateAdminStore();
    private final RateApprovalService service = new RateApprovalService(adminStore);
    private final OracleRateApprovalRunner runner = persistence.rateApprovalRunner();

    @BeforeEach
    void clean() {
        OracleTestSupport.cleanAll();
    }

    private CommRateRule register(EffectivePeriod period) {
        return persistence.inTx(() -> service.registerDraft(Direction.OUTBOUND,
                new InsurerCode("SAMLIFE"), new ProductKey("WL-20Y"), CommTypeCode.FY_COMM,
                null, Rate.of("7.0"), period));
    }

    private CommRateRule approvedOldVersion() {
        CommRateRule old = register(EffectivePeriod.from(OLD_FROM));
        persistence.inTx(() -> service.approve(old.rateId(), "최초승인"));
        return old;
    }

    @Test
    void 결정적_인터리빙_인덱스가_최종_심판하고_패자는_재시도로_수렴한다() throws Exception {
        CommRateRule old = approvedOldVersion();
        CommRateRule draftA = register(EffectivePeriod.from(NEW_FROM));
        CommRateRule draftB = register(EffectivePeriod.from(NEW_FROM));

        CountDownLatch aApproved = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // A: 승인 완료 후 커밋을 지연 — B의 앱 계층 검사가 A를 보지 못하는 창을 강제로 연다
            Future<?> holderA = pool.submit(() -> persistence.inTx(() -> {
                CommRateRule approved = service.approve(draftA.rateId(), "승인자A");
                aApproved.countDown();
                try {
                    releaseA.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return approved;
            }));
            assertThat(aApproved.await(60, TimeUnit.SECONDS)).isTrue();

            // B: 앱 계층 검사는 (A 미커밋이라) 통과 → 구버전 행 락 대기 → A 커밋 후
            //    ACTIVE 전이가 ux_comm_rate_active 위반 → 러너가 재시도로 수렴시킨다
            Future<CommRateRule> approvalB = pool.submit(() ->
                    runner.approve(draftB.rateId(), "승인자B"));
            Thread.sleep(1000); // B를 락 대기 상태에 붙잡아 둔다
            releaseA.countDown();
            holderA.get(60, TimeUnit.SECONDS);
            CommRateRule finalB = approvalB.get(60, TimeUnit.SECONDS);

            // 수렴: B(나중 승인)가 ACTIVE, A는 SUPERSEDED — 겹치는 ACTIVE 0건
            assertThat(finalB.status()).isEqualTo(RateStatus.ACTIVE);
            assertThat(adminStore.findById(draftA.rateId()).orElseThrow().status())
                    .isEqualTo(RateStatus.SUPERSEDED);

            // 구버전 트리밍은 정확히 1번 (패자의 중복 트리밍은 no-op이라 이력이 남지 않는다)
            CommRateRule trimmedOld = adminStore.findById(old.rateId()).orElseThrow();
            assertThat(trimmedOld.status()).isEqualTo(RateStatus.ACTIVE);
            assertThat(trimmedOld.period().applyTo()).isEqualTo(NEW_FROM.minusDays(1));
            List<RuleChangeEntry> oldHistory = adminStore.changeHistory(old.rateId());
            assertThat(oldHistory.stream()
                    .filter(e -> e.changeType() == RuleChangeEntry.ChangeType.TRIM)).hasSize(1);

            // 감사 궤적: A는 승인자A가 활성화했고 승인자B의 재승인이 밀어냈다
            List<RuleChangeEntry> aHistory = adminStore.changeHistory(draftA.rateId());
            assertThat(aHistory).anySatisfy(e -> {
                assertThat(e.changeType()).isEqualTo(RuleChangeEntry.ChangeType.ACTIVATE);
                assertThat(e.changedBy()).isEqualTo("승인자A");
            });
            assertThat(aHistory).anySatisfy(e -> {
                assertThat(e.changeType()).isEqualTo(RuleChangeEntry.ChangeType.SUPERSEDE);
                assertThat(e.changedBy()).isEqualTo("승인자B");
            });

            assertNoOverlappingActive(draftB.rateId());
        } finally {
            releaseA.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void 배리어_경합에서도_겹치는_ACTIVE는_0건으로_수렴한다() throws Exception {
        approvedOldVersion();
        CommRateRule draftC = register(EffectivePeriod.from(NEW_FROM));
        CommRateRule draftD = register(EffectivePeriod.from(NEW_FROM));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<CommRateRule> first = pool.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                return runner.approve(draftC.rateId(), "승인자C");
            });
            Future<CommRateRule> second = pool.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                return runner.approve(draftD.rateId(), "승인자D");
            });
            first.get(120, TimeUnit.SECONDS);
            second.get(120, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        RateStatus statusC = adminStore.findById(draftC.rateId()).orElseThrow().status();
        RateStatus statusD = adminStore.findById(draftD.rateId()).orElseThrow().status();
        assertThat(List.of(statusC, statusD))
                .containsExactlyInAnyOrder(RateStatus.ACTIVE, RateStatus.SUPERSEDED);

        long activeId = statusC == RateStatus.ACTIVE ? draftC.rateId() : draftD.rateId();
        assertNoOverlappingActive(activeId);
    }

    /** 겹치는 ACTIVE 0건 검증: 경계 전후 어느 기준일에도 Ambiguous 없이 정확히 1건이 조회된다. */
    private void assertNoOverlappingActive(long expectedActiveAfterSwitch) {
        for (LocalDate baseDate : List.of(NEW_FROM.minusDays(1), NEW_FROM, LocalDate.of(2026, 12, 1))) {
            CommRateRule found = persistence.inTx(() -> persistence.ruleRepository()
                    .findRate(Direction.OUTBOUND, new InsurerCode("SAMLIFE"), new ProductKey("WL-20Y"),
                            CommTypeCode.FY_COMM, null, baseDate))
                    .orElseThrow();
            if (!baseDate.isBefore(NEW_FROM)) {
                assertThat(found.rateId()).isEqualTo(expectedActiveAfterSwitch);
            }
        }
    }
}
