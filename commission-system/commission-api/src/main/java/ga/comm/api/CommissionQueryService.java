package ga.comm.api;

import ga.comm.calc.net.NetAmountCalculator;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.deferral.DeferralScheduleStore;
import ga.comm.deferral.ScheduleEntry;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.limit.LimitLedgerStore;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 조회 파사드 (설계서 §10 Phase 9) — 계약×설계사 단위로 계산 원장·한도 원장·분급 스케줄을
 * 한 화면 뷰로 합성한다. REST 컨트롤러(commission-api 웹 계층)는 이 파사드만 호출한다.
 */
public class CommissionQueryService {

    private final CommCalcStore calcStore;
    private final LimitLedgerStore ledgerStore;
    private final DeferralScheduleStore scheduleStore;

    public CommissionQueryService(CommCalcStore calcStore, LimitLedgerStore ledgerStore,
                                  DeferralScheduleStore scheduleStore) {
        this.calcStore = Objects.requireNonNull(calcStore);
        this.ledgerStore = Objects.requireNonNull(ledgerStore);
        this.scheduleStore = Objects.requireNonNull(scheduleStore);
    }

    public record LimitSummary(Money limitAmount, Money accumPaid, Money available,
                               LocalDate fyStart, LocalDate fyEnd) {
    }

    public record PolicyCommissionView(
            List<CommCalcRecord> records,
            Money netAmount,
            Optional<LimitSummary> limit,
            List<ScheduleEntry> deferralSchedules
    ) {
    }

    public PolicyCommissionView policyView(PolicyNo policyNo, AgentId agentId) {
        List<CommCalcRecord> records = calcStore.findByPolicyAndRecipient(policyNo, agentId.value());
        // 순액은 반드시 공용 컴포넌트 경유 — 상태 필터 SUM 금지(§3.0 합산 규약, 부록 B-4).
        // 직접 records.stream()...reduce로 합산하면 마감 전 정정 시 이중 차감 위험이 재도입된다.
        Money net = NetAmountCalculator.netOf(records);

        Optional<LimitSummary> limit = ledgerStore.find(policyNo, agentId)
                .map(l -> new LimitSummary(l.limitAmount(), l.accumPaid(), l.available(),
                        l.fyStart(), l.fyEnd()));

        return new PolicyCommissionView(records, net, limit,
                scheduleStore.findByPolicyAndAgent(policyNo, agentId));
    }
}
