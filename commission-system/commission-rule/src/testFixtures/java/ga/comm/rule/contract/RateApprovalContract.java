package ga.comm.rule.contract;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Rate;
import ga.comm.domain.type.Direction;
import ga.comm.rule.admin.CommRateAdminStore;
import ga.comm.rule.admin.RateApprovalService;
import ga.comm.rule.admin.RuleChangeEntry;
import ga.comm.rule.model.CommRateRule;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.RateStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 요율 승인 워크플로 계약 테스트 (설계서 §6.6, Phase 10b) — 트리밍/SUPERSEDE 시맨틱과
 * <b>변경 감사 이력</b>(전후 값·승인자)의 동작 동등성.
 * 승인 동시성(§6.6 v1.1.2 — 인덱스 최종 심판·재시도)은 인메모리로 재현할 수 없으므로
 * Oracle 전용 경합 테스트가 별도로 증명한다.
 */
public abstract class RateApprovalContract {

    protected static final LocalDate RULES_FROM = LocalDate.of(2026, 1, 1);
    protected static final LocalDate NEW_FROM = LocalDate.of(2026, 9, 1);

    protected abstract CommRateAdminStore adminStore();

    protected <T> T inTx(Supplier<T> work) {
        return work.get();
    }

    protected RateApprovalService service() {
        return new RateApprovalService(adminStore());
    }

    private CommRateRule draft(EffectivePeriod period) {
        return inTx(() -> service().registerDraft(Direction.OUTBOUND, new InsurerCode("SAMLIFE"),
                new ProductKey("WL-20Y"), CommTypeCode.FY_COMM, null, Rate.of("7.0"), period));
    }

    @Test
    void 승인은_기존_ACTIVE를_트리밍하고_전후값과_승인자를_이력으로_남긴다() {
        CommRateRule old = draft(EffectivePeriod.from(RULES_FROM));
        inTx(() -> service().approve(old.rateId(), "정산팀장A"));

        CommRateRule renewal = draft(EffectivePeriod.from(NEW_FROM));
        CommRateRule approved = inTx(() -> service().approve(renewal.rateId(), "정산팀장B"));

        // 트리밍 경계: 구 버전은 신규 개시일 전날까지 — 어느 날에도 겹치는 ACTIVE가 없다
        CommRateRule trimmed = adminStore().findById(old.rateId()).orElseThrow();
        assertThat(trimmed.status()).isEqualTo(RateStatus.ACTIVE);
        assertThat(trimmed.period().applyTo()).isEqualTo(NEW_FROM.minusDays(1));
        assertThat(approved.status()).isEqualTo(RateStatus.ACTIVE);
        assertThat(approved.period().applyFrom()).isEqualTo(NEW_FROM);

        // 변경 감사(§6.6): apply_to UPDATE의 전후 값 + 승인자
        List<RuleChangeEntry> history = adminStore().changeHistory(old.rateId());
        assertThat(history).anySatisfy(entry -> {
            assertThat(entry.changeType()).isEqualTo(RuleChangeEntry.ChangeType.TRIM);
            assertThat(entry.oldApplyTo()).isEqualTo(EffectivePeriod.MAX_DATE);
            assertThat(entry.newApplyTo()).isEqualTo(NEW_FROM.minusDays(1));
            assertThat(entry.changedBy()).isEqualTo("정산팀장B");
        });
        assertThat(adminStore().changeHistory(renewal.rateId())).anySatisfy(entry -> {
            assertThat(entry.changeType()).isEqualTo(RuleChangeEntry.ChangeType.ACTIVATE);
            assertThat(entry.oldStatus()).isEqualTo(RateStatus.DRAFT);
            assertThat(entry.newStatus()).isEqualTo(RateStatus.ACTIVE);
            assertThat(entry.changedBy()).isEqualTo("정산팀장B");
        });
    }

    @Test
    void 완전히_덮이는_ACTIVE는_SUPERSEDE되고_이력이_남는다() {
        CommRateRule old = draft(EffectivePeriod.from(NEW_FROM));
        inTx(() -> service().approve(old.rateId(), "정산팀장A"));

        CommRateRule replacement = draft(EffectivePeriod.from(NEW_FROM));
        inTx(() -> service().approve(replacement.rateId(), "정산팀장B"));

        assertThat(adminStore().findById(old.rateId()).orElseThrow().status())
                .isEqualTo(RateStatus.SUPERSEDED);
        assertThat(adminStore().changeHistory(old.rateId())).anySatisfy(entry -> {
            assertThat(entry.changeType()).isEqualTo(RuleChangeEntry.ChangeType.SUPERSEDE);
            assertThat(entry.oldStatus()).isEqualTo(RateStatus.ACTIVE);
            assertThat(entry.newStatus()).isEqualTo(RateStatus.SUPERSEDED);
            assertThat(entry.changedBy()).isEqualTo("정산팀장B");
        });
    }

    @Test
    void 기존_버전을_분할하는_승인은_거부되고_아무것도_바뀌지_않는다() {
        CommRateRule old = draft(EffectivePeriod.from(RULES_FROM));
        inTx(() -> service().approve(old.rateId(), "정산팀장A"));

        CommRateRule splitting = draft(EffectivePeriod.of(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 31)));

        assertThatThrownBy(() -> inTx(() -> service().approve(splitting.rateId(), "정산팀장B")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("분할");
        CommRateRule unchanged = adminStore().findById(old.rateId()).orElseThrow();
        assertThat(unchanged.status()).isEqualTo(RateStatus.ACTIVE);
        assertThat(unchanged.period().applyTo()).isEqualTo(EffectivePeriod.MAX_DATE);
        assertThat(adminStore().findById(splitting.rateId()).orElseThrow().status())
                .isEqualTo(RateStatus.DRAFT);
    }

    @Test
    void DRAFT가_아니면_승인할_수_없다() {
        CommRateRule rule = draft(EffectivePeriod.from(RULES_FROM));
        inTx(() -> service().approve(rule.rateId(), "정산팀장A"));

        assertThatThrownBy(() -> inTx(() -> service().approve(rule.rateId(), "정산팀장A")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void DRAFT_폐기는_DISCARD_이력을_남긴다() {
        CommRateRule rule = draft(EffectivePeriod.from(RULES_FROM));
        inTx(() -> {
            service().discardDraft(rule.rateId(), "정산팀장A");
            return null;
        });

        assertThat(adminStore().findById(rule.rateId()).orElseThrow().status())
                .isEqualTo(RateStatus.SUPERSEDED);
        assertThat(adminStore().changeHistory(rule.rateId())).anySatisfy(entry -> {
            assertThat(entry.changeType()).isEqualTo(RuleChangeEntry.ChangeType.DISCARD);
            assertThat(entry.changedBy()).isEqualTo("정산팀장A");
        });
    }

    @Test
    void 같은_키의_전체_버전_이력이_버전번호와_함께_보존된다() {
        CommRateRule first = draft(EffectivePeriod.from(RULES_FROM));
        inTx(() -> service().approve(first.rateId(), "정산팀장A"));
        CommRateRule second = draft(EffectivePeriod.from(NEW_FROM));
        inTx(() -> service().approve(second.rateId(), "정산팀장A"));

        List<CommRateRule> versions = adminStore().findByKey(first.key());
        assertThat(versions).hasSize(2);
        assertThat(versions).extracting(CommRateRule::versionNo).containsExactlyInAnyOrder(1L, 2L);
    }
}
