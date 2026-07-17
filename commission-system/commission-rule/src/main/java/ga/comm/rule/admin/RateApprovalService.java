package ga.comm.rule.admin;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Rate;
import ga.comm.domain.type.Direction;
import ga.comm.rule.model.CommRateRule;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.RateKey;
import ga.comm.rule.model.RateStatus;

import java.util.List;
import java.util.Objects;

/**
 * 요율 승인 워크플로: DRAFT 등록 → 승인 시 기존 ACTIVE 버전을 트리밍/대체(SUPERSEDE).
 *
 * <p>불변식: 같은 업무 키(RateKey) 안에서 유효기간이 겹치는 ACTIVE 레코드는 승인 후에도 존재하지 않는다.
 */
public class RateApprovalService {

    private final CommRateAdminStore store;

    public RateApprovalService(CommRateAdminStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public CommRateRule registerDraft(Direction direction, InsurerCode insurerCd, ProductKey productKey,
                                      CommTypeCode commType, Integer installmentNo,
                                      Rate rate, EffectivePeriod period) {
        RateKey key = new RateKey(direction, insurerCd, productKey, commType, installmentNo);
        long nextVersion = store.findByKey(key).stream()
                .mapToLong(CommRateRule::versionNo)
                .max()
                .orElse(0L) + 1;
        CommRateRule draft = new CommRateRule(store.nextRateId(), direction, insurerCd, productKey,
                commType, installmentNo, rate, period, nextVersion, RateStatus.DRAFT);
        store.insert(draft);
        return draft;
    }

    public CommRateRule approve(long rateId) {
        return approve(rateId, "system");
    }

    /**
     * 승인: DRAFT를 ACTIVE로 전이하고, 기간이 겹치는 기존 ACTIVE 버전을 정리한다.
     * <ul>
     *   <li>기존 버전이 신규 개시일보다 먼저 시작 → 종료일을 신규 개시일 전날로 트리밍</li>
     *   <li>기존 버전이 신규 기간에 완전히 덮임 → SUPERSEDED</li>
     *   <li>신규 기간이 기존 버전을 분할(기존이 신규 종료 후에도 계속) → 미지원, 승인 거부</li>
     * </ul>
     * 모든 교체는 승인자와 함께 저장되어 룰 변경 이력에 남는다 (§6.6 트리밍 감사).
     *
     * <p>동시성(§6.6 v1.1.2): 같은 키의 동시 승인 2건은 이 검사를 둘 다 통과할 수 있다.
     * 최종 심판은 DB의 function-based unique index이며, 인덱스 위반은 예외가 아니라
     * 정상 경합이다 — 호출측(어댑터 러너)이 최신 상태 재조회 후 재검증(재시도)하거나
     * 명시적으로 거부한다.
     */
    public CommRateRule approve(long rateId, String approvedBy) {
        CommRateRule draft = store.findById(rateId)
                .orElseThrow(() -> new IllegalArgumentException("요율이 존재하지 않습니다: " + rateId));
        if (draft.status() != RateStatus.DRAFT) {
            throw new IllegalStateException("DRAFT 상태만 승인할 수 있습니다: " + rateId + " " + draft.status());
        }

        List<CommRateRule> overlappingActives = store.findByKey(draft.key()).stream()
                .filter(r -> r.status() == RateStatus.ACTIVE)
                .filter(r -> r.period().overlaps(draft.period()))
                .toList();

        for (CommRateRule active : overlappingActives) {
            if (active.period().applyTo().isAfter(draft.period().applyTo())) {
                throw new IllegalStateException(
                        "기존 ACTIVE 버전(" + active.rateId() + ", " + active.period() + ")을 분할하는 승인은 "
                                + "지원하지 않습니다. 신규 버전(" + draft.period() + ")은 기존 버전 이후를 덮거나 "
                                + "개시일 이전 구간만 남겨야 합니다.");
            }
        }

        for (CommRateRule active : overlappingActives) {
            if (active.period().applyFrom().isBefore(draft.period().applyFrom())) {
                store.replace(active.withPeriod(
                        active.period().truncatedBefore(draft.period().applyFrom())), approvedBy);
            } else {
                store.replace(active.withStatus(RateStatus.SUPERSEDED), approvedBy);
            }
        }

        CommRateRule approved = draft.withStatus(RateStatus.ACTIVE);
        store.replace(approved, approvedBy);
        return approved;
    }

    public void discardDraft(long rateId) {
        discardDraft(rateId, "system");
    }

    public void discardDraft(long rateId, String discardedBy) {
        CommRateRule draft = store.findById(rateId)
                .orElseThrow(() -> new IllegalArgumentException("요율이 존재하지 않습니다: " + rateId));
        if (draft.status() != RateStatus.DRAFT) {
            throw new IllegalStateException("DRAFT 상태만 폐기할 수 있습니다: " + rateId + " " + draft.status());
        }
        store.replace(draft.withStatus(RateStatus.SUPERSEDED), discardedBy);
    }
}
