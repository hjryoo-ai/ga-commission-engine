package ga.comm.app.config;

import ga.comm.api.CommissionQueryService;
import ga.comm.api.SimulationService;
import ga.comm.api.disclosure.DisclosureService;
import ga.comm.api.disclosure.GradingPolicy;
import ga.comm.api.disclosure.RankingService;
import ga.comm.api.disclosure.TercileGradingPolicy;
import ga.comm.calc.revision.RevisionService;
import ga.comm.calc.store.CloseStatusProvider;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.calc.store.PolicyEventStore;
import ga.comm.clawback.ClawbackOffsetService;
import ga.comm.clawback.ClawbackReceivableStore;
import ga.comm.deferral.DeferralReleaseService;
import ga.comm.deferral.DeferralScheduleStore;
import ga.comm.deferral.PolicyStatusProvider;
import ga.comm.limit.LimitLedgerStore;
import ga.comm.limit.LimitReversalHook;
import ga.comm.rule.RuleRepository;
import ga.comm.rule.admin.CommRateAdminStore;
import ga.comm.rule.admin.RateApprovalService;
import ga.comm.settlement.AdjustmentService;
import ga.comm.settlement.AgentSettlementStore;
import ga.comm.settlement.CloseHook;
import ga.comm.settlement.DeferralReleaseCloseHook;
import ga.comm.settlement.MonthCloseService;
import ga.comm.settlement.PayoutService;
import ga.comm.settlement.SettleCloseStore;
import ga.comm.settlement.WithholdingTaxPolicy;
import ga.comm.shadow.ShadowRunService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 서비스 계층 배선 (Phase 16) — 조회·시뮬레이션·승인·마감·지급·정정·조정·공시·순위·섀도 서비스를
 * 스토어 빈 위에 조립한다. 트랜잭션 경계는 서비스가 아니라 호출부(배치 태스크릿/컨트롤러)가 잡는다.
 */
@Configuration(proxyBeanMethods = false)
public class ServiceConfig {

    @Bean
    public CommissionQueryService commissionQueryService(CommCalcStore calcStore,
                                                         LimitLedgerStore ledgerStore,
                                                         DeferralScheduleStore scheduleStore) {
        return new CommissionQueryService(calcStore, ledgerStore, scheduleStore);
    }

    @Bean
    public SimulationService simulationService(RuleRepository rules) {
        return new SimulationService(rules);
    }

    @Bean
    public RateApprovalService rateApprovalService(CommRateAdminStore store) {
        return new RateApprovalService(store);
    }

    @Bean
    public ClawbackOffsetService clawbackOffsetService(ClawbackReceivableStore store) {
        return new ClawbackOffsetService(store);
    }

    /**
     * 분급 유지 조건 판정 — <b>운영 placeholder</b>. 실 운영에서는 계약 상태 원장(실효/해약/부활)을
     * 조회해 도래 시점의 유지 여부를 판정해야 한다. 현재는 항상 유지로 두며, 실 판정 연동은 운영 전환
     * 체크리스트 항목이다(§8.5 이후). 스모크·마감 경로(분급 미도래)에는 영향이 없다.
     */
    @Bean
    public PolicyStatusProvider policyStatusProvider() {
        return (policyNo, asOf) -> true;
    }

    @Bean
    public DeferralReleaseService deferralReleaseService(DeferralScheduleStore scheduleStore,
                                                         CommCalcStore calcStore,
                                                         PolicyStatusProvider policyStatus) {
        return new DeferralReleaseService(scheduleStore, calcStore, policyStatus);
    }

    /** 마감 훅 — 도래 분급분을 지급 파이프라인에 투입한다(§7). */
    @Bean
    public List<CloseHook> closeHooks(DeferralReleaseService releaseService) {
        return List.of(new DeferralReleaseCloseHook(releaseService));
    }

    @Bean
    public MonthCloseService monthCloseService(CommCalcStore calcStore, PolicyEventStore eventStore,
                                               LimitLedgerStore ledgerStore, SettleCloseStore closeStore,
                                               List<CloseHook> closeHooks) {
        return new MonthCloseService(calcStore, eventStore, ledgerStore, closeStore, closeHooks);
    }

    @Bean
    public PayoutService payoutService(CommCalcStore calcStore, AgentSettlementStore settlementStore,
                                       SettleCloseStore closeStore, ClawbackOffsetService offsetService) {
        // 원천세는 지급 런별 순지급액 기준 3.3% (§6.3, §11.11 세무 확인 시 정책 교체).
        return new PayoutService(calcStore, settlementStore, closeStore, offsetService,
                WithholdingTaxPolicy.STANDARD_3_3);
    }

    @Bean
    public RevisionService revisionService(CommCalcStore calcStore, CloseStatusProvider closeStatus,
                                           LimitLedgerStore ledgerStore) {
        return new RevisionService(calcStore, closeStatus, List.of(new LimitReversalHook(ledgerStore)));
    }

    @Bean
    public AdjustmentService adjustmentService(CommCalcStore calcStore, CloseStatusProvider closeStatus,
                                               AdjustmentService.AdjustmentStore adjustmentStore) {
        return new AdjustmentService(calcStore, closeStatus, adjustmentStore);
    }

    @Bean
    public DisclosureService disclosureService(CommCalcStore calcStore, PolicyEventStore eventStore) {
        return new DisclosureService(calcStore, eventStore);
    }

    @Bean
    public GradingPolicy gradingPolicy() {
        // 등급 산정 기준(분위/절대/상대)은 외부 확정 사안(§11 #13) — 기본 3분위.
        return new TercileGradingPolicy();
    }

    @Bean
    public RankingService rankingService(GradingPolicy gradingPolicy) {
        return new RankingService(gradingPolicy);
    }

    @Bean
    public ShadowRunService shadowRunService(CommCalcStore calcStore) {
        return new ShadowRunService(calcStore);
    }
}
