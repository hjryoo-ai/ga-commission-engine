package ga.comm.clawback;

import ga.comm.calc.CalcContext;
import ga.comm.calc.CalcResultListener;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Money;
import ga.comm.limit.LimitLedger;
import ga.comm.limit.LimitLedgerService;
import ga.comm.rule.model.LimitRule;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 환수 저장 직후 한도 원장 연동 (설계서 §6.3):
 * 환수(음수 CLAWBACK) → 원장 차감(한도 여유 복원), 부활 재지급(양수) → 원장 재가산.
 * 복원 여부 자체가 LimitRule의 파라미터(clawbackRestores)다.
 *
 * <p>단순화: 환수 기준액의 유형 구성(전부 한도 포함 유형 가정)으로 전액을 차감한다.
 * 한도 미포함 유형이 환수 대상에 추가되면 유형별 분해가 필요하다 — V2 확장 지점.
 */
public class ClawbackPostProcessor implements CalcResultListener {

    private final LimitLedgerService ledgerService;

    public ClawbackPostProcessor(LimitLedgerService ledgerService) {
        this.ledgerService = Objects.requireNonNull(ledgerService);
    }

    @Override
    public void onPersisted(CalcContext ctx, List<PersistedLine> lines) {
        Optional<LimitRule> ruleOpt = ctx.rules().limitRule();
        if (ruleOpt.isEmpty()) {
            return;
        }
        LimitRule rule = ruleOpt.get();
        AgentId agentId = new AgentId(ctx.target().recipient().id());

        for (PersistedLine persisted : lines) {
            if (!persisted.record().commType().equals(CommTypeCode.CLAWBACK)) {
                continue;
            }
            Money amount = persisted.record().calcAmount();
            if (amount.isNegative()) {
                ledgerService.deductForClawback(ctx.event().policyNo(), agentId,
                        ctx.event().eventDate(), rule, persisted.record().calcId(), amount.abs());
            } else if (amount.isPositive()) {
                ledgerService.repostForRevive(ctx.event().policyNo(), agentId,
                        ctx.event().eventDate(), rule, persisted.record().calcId(), amount);
            }
        }
    }

    /** 원장 조회 편의 (테스트/리포트). */
    public Optional<LimitLedger> ledgerOf(CalcContext ctx) {
        return ledgerService.find(ctx.event().policyNo(),
                new AgentId(ctx.target().recipient().id()));
    }
}
