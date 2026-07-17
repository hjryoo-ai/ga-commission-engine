package ga.comm.rule.admin;

import ga.comm.rule.model.CommRateRule;
import ga.comm.rule.model.RateStatus;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * 룰 변경 이력 1건 (설계서 §6.6 v1.1.2 — 트리밍의 변경 감사).
 *
 * <p>트리밍은 기존 ACTIVE 행의 apply_to를 UPDATE하는 예외적 변경이므로 변경 전후 값·승인자·시각을
 * 반드시 남긴다. 계산 재현성은 COMM_CALC.rule_versions 박제가 이미 보장하지만,
 * "지난달 룰 테이블이 어떤 모습이었나"(시스템 시간축, §3.1)에 답하려면 이 이력이 필요하다.
 * 기록 시각은 저장소가 부여한다(DB: changed_at DEFAULT CURRENT_TIMESTAMP).
 */
public record RuleChangeEntry(
        long rateId,
        ChangeType changeType,
        LocalDate oldApplyTo,
        LocalDate newApplyTo,
        RateStatus oldStatus,
        RateStatus newStatus,
        String changedBy
) {
    public enum ChangeType {
        /** ACTIVE 기간 트리밍 — apply_to를 신규 버전 개시일 전날로 당김 */
        TRIM,
        /** ACTIVE가 신규 버전에 완전히 덮임 */
        SUPERSEDE,
        /** DRAFT → ACTIVE 승인 */
        ACTIVATE,
        /** DRAFT 폐기 */
        DISCARD
    }

    public RuleChangeEntry {
        Objects.requireNonNull(changeType, "changeType");
        Objects.requireNonNull(oldApplyTo, "oldApplyTo");
        Objects.requireNonNull(newApplyTo, "newApplyTo");
        Objects.requireNonNull(oldStatus, "oldStatus");
        Objects.requireNonNull(newStatus, "newStatus");
        Objects.requireNonNull(changedBy, "changedBy");
    }

    /**
     * 교체 전후 diff에서 이력을 도출한다 — 저장소 구현이 replace 시점에 호출해
     * 감사 기록 누락이 구조적으로 불가능하게 한다. 승인 워크플로가 만들어내는 변경
     * (전이·트리밍) 외의 diff는 불변 규약 위반이므로 예외.
     */
    public static Optional<RuleChangeEntry> diff(CommRateRule before, CommRateRule after, String changedBy) {
        if (before.rateId() != after.rateId()) {
            throw new IllegalArgumentException(
                    "다른 rateId의 diff: " + before.rateId() + " vs " + after.rateId());
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
                throw new IllegalStateException("허용되지 않는 상태 전이: "
                        + before.status() + " → " + after.status() + " (rateId=" + before.rateId() + ")");
            }
        } else if (!before.period().applyTo().equals(after.period().applyTo())) {
            type = ChangeType.TRIM;
        } else {
            return Optional.empty();
        }
        return Optional.of(new RuleChangeEntry(before.rateId(), type,
                before.period().applyTo(), after.period().applyTo(),
                before.status(), after.status(), changedBy));
    }
}
