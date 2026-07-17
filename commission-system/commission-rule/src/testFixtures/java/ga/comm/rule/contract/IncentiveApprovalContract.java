package ga.comm.rule.contract;

import ga.comm.domain.money.Money;
import ga.comm.rule.admin.IncentiveAdminStore;
import ga.comm.rule.admin.IncentiveApprovalService;
import ga.comm.rule.admin.IncentiveChangeEntry;
import ga.comm.rule.incentive.InvalidConditionException;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.IncentiveKey;
import ga.comm.rule.model.IncentiveRule;
import ga.comm.rule.model.RateStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시책 승인 워크플로 계약 (설계서 §6.6, §8.7) — 요율 승인 계약과 동일한 불변식을 시책에 적용한다.
 * 인메모리 레퍼런스와 Oracle 어댑터가 동일하게 상속·통과한다.
 */
public abstract class IncentiveApprovalContract {

    private static final LocalDate JAN = LocalDate.of(2026, 1, 1);
    private static final LocalDate JUN = LocalDate.of(2026, 6, 1);
    private static final String COND = "premium >= 0"; // 항상 참 (승인 워크플로만 검증)

    protected abstract IncentiveAdminStore adminStore();

    protected <T> T inTx(Supplier<T> work) {
        return work.get();
    }

    protected IncentiveApprovalService service() {
        return new IncentiveApprovalService(adminStore());
    }

    private IncentiveRule draft(String cd, LocalDate from) {
        return service().registerDraftFixed(cd, null, null, null, COND, Money.won(100_000),
                EffectivePeriod.from(from));
    }

    @Test
    void 승인은_기존_ACTIVE를_트리밍하고_전후값과_승인자를_이력으로_남긴다() {
        long v1 = inTx(() -> service().approve(draft("Q1-PUSH", JAN).incentiveId(), "팀장A")).incentiveId();
        long v2 = inTx(() -> service().approve(draft("Q1-PUSH", JUN).incentiveId(), "팀장B")).incentiveId();

        assertThat(inTx(() -> adminStore().findById(v1)).orElseThrow().period().applyTo())
                .isEqualTo(JUN.minusDays(1)); // 개시 전날로 트리밍
        assertThat(inTx(() -> adminStore().findById(v1)).orElseThrow().status())
                .isEqualTo(RateStatus.ACTIVE);
        assertThat(inTx(() -> adminStore().findById(v2)).orElseThrow().status())
                .isEqualTo(RateStatus.ACTIVE);

        List<IncentiveChangeEntry> trimHist = inTx(() -> adminStore().changeHistory(v1));
        assertThat(trimHist).anySatisfy(e -> {
            assertThat(e.changeType()).isEqualTo(IncentiveChangeEntry.ChangeType.TRIM);
            assertThat(e.newApplyTo()).isEqualTo(JUN.minusDays(1));
            assertThat(e.changedBy()).isEqualTo("팀장B");
        });
        assertThat(inTx(() -> adminStore().changeHistory(v2))).anySatisfy(e ->
                assertThat(e.changeType()).isEqualTo(IncentiveChangeEntry.ChangeType.ACTIVATE));
    }

    @Test
    void 완전히_덮이는_ACTIVE는_SUPERSEDE되고_이력이_남는다() {
        long v1 = inTx(() -> service().approve(draft("SAME", JAN).incentiveId(), "팀장A")).incentiveId();
        inTx(() -> service().approve(draft("SAME", JAN).incentiveId(), "팀장B")); // 같은 개시 → 완전 덮임

        assertThat(inTx(() -> adminStore().findById(v1)).orElseThrow().status())
                .isEqualTo(RateStatus.SUPERSEDED);
        assertThat(inTx(() -> adminStore().changeHistory(v1))).anySatisfy(e ->
                assertThat(e.changeType()).isEqualTo(IncentiveChangeEntry.ChangeType.SUPERSEDE));
    }

    @Test
    void 기존_버전을_분할하는_승인은_거부되고_아무것도_바뀌지_않는다() {
        long v1 = inTx(() -> service().approve(draft("SPLIT", JAN).incentiveId(), "팀장A")).incentiveId();
        IncentiveRule splitting = inTx(() -> service().registerDraftFixed("SPLIT", null, null, null,
                COND, Money.won(100_000), EffectivePeriod.of(JUN, LocalDate.of(2026, 8, 31))));

        assertThatThrownBy(() -> inTx(() -> {
            service().approve(splitting.incentiveId(), "팀장B");
            return null;
        })).isInstanceOf(IllegalStateException.class);

        assertThat(inTx(() -> adminStore().findById(v1)).orElseThrow().period().applyTo())
                .isEqualTo(EffectivePeriod.MAX_DATE); // 원본 불변
        assertThat(inTx(() -> adminStore().findById(splitting.incentiveId())).orElseThrow().status())
                .isEqualTo(RateStatus.DRAFT);
    }

    @Test
    void DRAFT가_아니면_승인할_수_없다() {
        IncentiveRule approved = inTx(() -> service().approve(draft("ONCE", JAN).incentiveId(), "팀장A"));
        assertThatThrownBy(() -> inTx(() -> service().approve(approved.incentiveId(), "팀장B")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void DRAFT_폐기는_DISCARD_이력을_남긴다() {
        long id = inTx(() -> draft("DISCARD-ME", JAN)).incentiveId();
        inTx(() -> {
            service().discardDraft(id, "팀장A");
            return null;
        });

        assertThat(inTx(() -> adminStore().findById(id)).orElseThrow().status())
                .isEqualTo(RateStatus.SUPERSEDED);
        assertThat(inTx(() -> adminStore().changeHistory(id))).anySatisfy(e ->
                assertThat(e.changeType()).isEqualTo(IncentiveChangeEntry.ChangeType.DISCARD));
    }

    @Test
    void 같은_키의_전체_버전_이력이_버전번호와_함께_보존된다() {
        inTx(() -> service().approve(draft("HIST", JAN).incentiveId(), "팀장A"));
        inTx(() -> service().approve(draft("HIST", JUN).incentiveId(), "팀장B"));

        assertThat(inTx(() -> adminStore().findByKey(new IncentiveKey("HIST")))
                .stream().map(IncentiveRule::versionNo)).containsExactly(1L, 2L);
    }

    @Test
    void 부적합_조건식은_등록_시점에_거부된다() {
        // 위험 구문(타입 참조)은 등록 fail-fast — 승인까지 가지 못한다
        assertThatThrownBy(() -> inTx(() -> service().registerDraftFixed("BAD", null, null, null,
                "T(java.lang.Runtime).getRuntime()", Money.won(100_000), EffectivePeriod.from(JAN))))
                .isInstanceOf(InvalidConditionException.class);
    }
}
