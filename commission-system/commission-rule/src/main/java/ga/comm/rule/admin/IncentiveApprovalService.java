package ga.comm.rule.admin;

import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.rule.incentive.IncentiveConditionEvaluator;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.IncentiveKey;
import ga.comm.rule.model.IncentiveRule;
import ga.comm.rule.model.PayoutKind;
import ga.comm.rule.model.RateStatus;

import java.util.List;
import java.util.Objects;

/**
 * 시책 승인 워크플로 (Phase 13). 요율 승인({@code RateApprovalService})과 <b>동일 패턴</b>:
 * 트리밍/SUPERSEDE만 허용(기간 분할 승인 거부), diff 기반 변경 감사, "같은 코드에 겹치는 ACTIVE 없음"
 * 불변식. 추가로 <b>조건식 fail-fast 검증</b>을 등록·승인 두 시점에 수행한다(부록 B-13) — 부적합·
 * 위험 조건식은 승인되지 않는다.
 */
public class IncentiveApprovalService {

    private final IncentiveAdminStore store;
    private final IncentiveConditionEvaluator evaluator = new IncentiveConditionEvaluator();

    public IncentiveApprovalService(IncentiveAdminStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public IncentiveRule registerDraftFixed(String incentiveCd, InsurerCode insurerCd,
                                            ProductKey productKey, String channel,
                                            String conditionExpr, Money fixedAmount,
                                            EffectivePeriod period) {
        return registerDraft(incentiveCd, insurerCd, productKey, channel, conditionExpr,
                PayoutKind.FIXED, fixedAmount, null, period);
    }

    public IncentiveRule registerDraftPremiumRate(String incentiveCd, InsurerCode insurerCd,
                                                  ProductKey productKey, String channel,
                                                  String conditionExpr, Rate premiumRate,
                                                  EffectivePeriod period) {
        return registerDraft(incentiveCd, insurerCd, productKey, channel, conditionExpr,
                PayoutKind.PREMIUM_RATE, null, premiumRate, period);
    }

    private IncentiveRule registerDraft(String incentiveCd, InsurerCode insurerCd,
                                        ProductKey productKey, String channel, String conditionExpr,
                                        PayoutKind payoutKind, Money fixedAmount, Rate premiumRate,
                                        EffectivePeriod period) {
        // fail-fast: 등록 시점에 조건식 파싱·샌드박스 시평가 (불가 시 등록 거부)
        evaluator.validate(conditionExpr);

        long nextVersion = store.findByKey(new IncentiveKey(incentiveCd)).stream()
                .mapToLong(IncentiveRule::versionNo).max().orElse(0L) + 1;
        IncentiveRule draft = new IncentiveRule(store.nextIncentiveId(), incentiveCd, insurerCd,
                productKey, channel, conditionExpr, payoutKind, fixedAmount, premiumRate, period,
                nextVersion, RateStatus.DRAFT);
        store.insert(draft);
        return draft;
    }

    public IncentiveRule approve(long incentiveId, String approvedBy) {
        IncentiveRule draft = store.findById(incentiveId)
                .orElseThrow(() -> new IllegalArgumentException("시책이 없습니다: " + incentiveId));
        if (draft.status() != RateStatus.DRAFT) {
            throw new IllegalStateException("DRAFT 상태만 승인할 수 있습니다: " + incentiveId
                    + " " + draft.status());
        }
        // fail-fast: 활성화 직전 재검증 (직접 삽입 등 우회 경로 대비)
        evaluator.validate(draft.conditionExpr());

        List<IncentiveRule> overlappingActives = store.findByKey(draft.key()).stream()
                .filter(r -> r.status() == RateStatus.ACTIVE)
                .filter(r -> r.period().overlaps(draft.period()))
                .toList();

        // 분할 승인 거부 먼저 (변이 이전) — 아무것도 바뀌지 않는 원자적 거부
        for (IncentiveRule active : overlappingActives) {
            if (active.period().applyTo().isAfter(draft.period().applyTo())) {
                throw new IllegalStateException(
                        "기존 버전 기간을 분할하는 승인은 허용되지 않습니다: " + draft.key());
            }
        }
        for (IncentiveRule active : overlappingActives) {
            if (active.period().applyFrom().isBefore(draft.period().applyFrom())) {
                store.replace(active.withPeriod(
                        active.period().truncatedBefore(draft.period().applyFrom())), approvedBy);
            } else {
                store.replace(active.withStatus(RateStatus.SUPERSEDED), approvedBy);
            }
        }
        IncentiveRule activated = draft.withStatus(RateStatus.ACTIVE);
        store.replace(activated, approvedBy);
        return activated;
    }

    public void discardDraft(long incentiveId, String discardedBy) {
        IncentiveRule draft = store.findById(incentiveId)
                .orElseThrow(() -> new IllegalArgumentException("시책이 없습니다: " + incentiveId));
        if (draft.status() != RateStatus.DRAFT) {
            throw new IllegalStateException("DRAFT 상태만 폐기할 수 있습니다: " + incentiveId
                    + " " + draft.status());
        }
        store.replace(draft.withStatus(RateStatus.SUPERSEDED), discardedBy);
    }
}
