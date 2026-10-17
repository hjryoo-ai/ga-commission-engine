package ga.comm.settlement.simulation;

import ga.comm.domain.time.CloseYm;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 시뮬레이션 요청·결과(포털 계약 v1.3 {@code NewContractRequest}·{@code NewContractResult}·{@code PolicyEventRequest}·
 * {@code PolicyEventResult}와 같은 필드). 금액은 정수 원(long). 선택 필드는 null = 없음.
 */
public final class SimulationModel {

    private SimulationModel() {
    }

    public record NewContractRequest(String agentId, String productKey, long monthlyPremium, LocalDate contractDate,
                                     Integer paymentTermMonths, String insurerCd, List<String> incentiveCodes) {
        public NewContractRequest {
            Objects.requireNonNull(agentId);
            Objects.requireNonNull(productKey);
            Objects.requireNonNull(contractDate);
            incentiveCodes = incentiveCodes == null ? null : List.copyOf(incentiveCodes);
        }
    }

    public record Installment(int no, long amount) {
    }

    public record Incentive(String code, long amount, long limitCutAmt, boolean limitIncluded) {
    }

    public record DueSlice(CloseYm dueYm, long amount) {
    }

    public record Deferral(long firstYearPayout, List<DueSlice> schedule) {
        public Deferral {
            schedule = List.copyOf(schedule);
        }
    }

    /** @param limitAmount    한도 미적용 계약(원장을 열지 않는 계약)이면 null
     *  @param projectedAccum limitAmount가 null이면 null */
    public record NewContractResult(Long limitAmount, List<Installment> fyCommissionByInstallment, List<Incentive> incentives,
                                    Long projectedAccum, Deferral deferral, String ruleSnapshotId, String generatedAt) {
        public NewContractResult {
            fyCommissionByInstallment = List.copyOf(fyCommissionByInstallment);
            incentives = List.copyOf(incentives);
        }
    }

    /** eventType은 계약의 열거 그대로(문자열) — 엔진 열거로의 변환·거부는 시뮬레이터가 한다. */
    public record PolicyEventRequest(String agentId, String policyNo, String eventType, LocalDate asOfDate,
                                     Integer elapsedInstallments, Long newMonthlyPremium) {
        public PolicyEventRequest {
            Objects.requireNonNull(agentId);
            Objects.requireNonNull(policyNo);
            Objects.requireNonNull(eventType);
            Objects.requireNonNull(asOfDate);
        }
    }

    public record MonthEffect(CloseYm closeYm, long before, long after) {
    }

    public record CancelledDeferral(String scheduleId, CloseYm dueYm, long amount) {
    }

    public record PolicyEventResult(long clawbackAmount, MonthEffect offsetAgainstMonth, long receivableRemaining,
                                    List<CancelledDeferral> cancelledDeferrals, String ruleSnapshotId) {
        public PolicyEventResult {
            cancelledDeferrals = List.copyOf(cancelledDeferrals);
        }
    }
}
