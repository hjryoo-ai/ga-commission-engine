package ga.comm.rule.admin;

import ga.comm.rule.model.IncentiveRule;
import ga.comm.rule.model.RateStatus;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 시책 마스터 변경 이력 1행 (INCENTIVE_CHANGE_HIST, Phase 13). 요율의 {@code RuleChangeEntry}와
 * 동일한 diff 기반 감사 패턴(§6.6 v1.1.3 "다른 마스터 동일 적용")을 이행한다 — 변경 이력은
 * 저장소 replace의 전후 diff에서 자동 도출되어 기록 누락이 구조적으로 불가능하다.
 */
public record IncentiveChangeEntry(
        long incentiveId,
        ChangeType changeType,
        LocalDate oldApplyTo,
        LocalDate newApplyTo,
        RateStatus oldStatus,
        RateStatus newStatus,
        String changedBy
) {

    public enum ChangeType {
        TRIM,        // ACTIVE 기간 축소 (신 버전 개시 전날로)
        SUPERSEDE,   // ACTIVE → SUPERSEDED (완전 덮임)
        ACTIVATE,    // DRAFT → ACTIVE (승인)
        DISCARD      // DRAFT → SUPERSEDED (폐기)
    }

    /**
     * 교체 전후에서 변경 유형을 도출한다. 상태 변경이 기간 변경보다 우선하며, 허용된 4가지 외의
     * 상태 전이는 저장 경계에서 거부한다(워크플로가 만든 변경만 허용). 전후가 동일하면 이력 없음
     * (중복 트리밍이 멱등 — 경합 패자의 재트리밍이 이력을 오염시키지 않는다).
     */
    public static Optional<IncentiveChangeEntry> diff(IncentiveRule before, IncentiveRule after,
                                                      String changedBy) {
        if (before.incentiveId() != after.incentiveId()) {
            throw new IllegalArgumentException("서로 다른 시책의 diff는 만들 수 없습니다");
        }
        ChangeType type;
        if (before.status() != after.status()) {
            if (after.status() == RateStatus.ACTIVE && before.status() == RateStatus.DRAFT) {
                type = ChangeType.ACTIVATE;
            } else if (after.status() == RateStatus.SUPERSEDED && before.status() == RateStatus.ACTIVE) {
                type = ChangeType.SUPERSEDE;
            } else if (after.status() == RateStatus.SUPERSEDED && before.status() == RateStatus.DRAFT) {
                type = ChangeType.DISCARD;
            } else {
                throw new IllegalStateException(
                        "허용되지 않는 상태 전이: " + before.status() + "→" + after.status());
            }
        } else if (!before.period().applyTo().equals(after.period().applyTo())) {
            type = ChangeType.TRIM;
        } else {
            return Optional.empty(); // no-op replace → 이력 없음
        }
        return Optional.of(new IncentiveChangeEntry(before.incentiveId(), type,
                before.period().applyTo(), after.period().applyTo(),
                before.status(), after.status(), changedBy));
    }
}
