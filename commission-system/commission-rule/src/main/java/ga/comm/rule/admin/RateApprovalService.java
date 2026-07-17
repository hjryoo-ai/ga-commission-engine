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

    /** 시드/테스트 대역 승인 — "system"으로 기록한다(§6.6 실명 요건의 예외 경로). */
    public CommRateRule approve(long rateId) {
        return approveInternal(rateId, "system");
    }

    /**
     * 운영 승인 — DRAFT를 ACTIVE로 전이하고 겹치는 기존 ACTIVE 버전을 정리한다.
     *
     * <p><b>실승인자 필수(§6.6, 서비스 계층 강제)</b>: {@code approvedBy}는 실명이어야 한다 — 빈 값·
     * "system"은 거부한다. 컨트롤러의 principal 검증에 더한 <b>심층 방어</b>다: 기술 계정 principal이
     * "system"으로 들어오거나 컨트롤러를 거치지 않는 호출(러너·배치)이 있어도, 승인 기록이 시드/테스트
     * 대역("system")과 뒤섞이지 않는다. 시드/테스트는 인자 없는 {@link #approve(long)}를 쓴다.
     *
     * <p>정리 규칙: 기존 버전이 신규 개시일보다 먼저 시작 → 종료일 트리밍 / 신규 기간에 완전히 덮임 →
     * SUPERSEDED / 기존을 분할(신규 종료 후에도 계속) → 미지원, 거부. 교체는 승인자와 함께 이력에 남는다.
     *
     * <p>동시성(§6.6): 같은 키 동시 승인 2건은 이 검사를 둘 다 통과할 수 있다. 최종 심판은 DB의
     * function-based unique index이며, 위반은 정상 경합으로 러너가 재조회·재검증(재시도)하거나 거부한다.
     */
    public CommRateRule approve(long rateId, String approvedBy) {
        ApproverPolicy.requireReal(approvedBy);
        return approveInternal(rateId, approvedBy.trim());
    }

    private CommRateRule approveInternal(long rateId, String approvedBy) {
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
