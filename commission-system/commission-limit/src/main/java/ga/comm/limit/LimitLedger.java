package ga.comm.limit;

import ga.comm.calc.LimitLedgerView;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.RoundingPolicy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 1200% 한도 원장 (계약×설계사, 초년도 윈도우) — LIMIT_LEDGER + DTL 애그리게잇.
 *
 * <p>불변식 (설계서 §6.1):
 * <ul>
 *   <li>양수 전기 후에도 {@code accumPaid <= limitAmount} (감액 재산정으로 인한 기존 초과는 예외적 허용 상태)</li>
 *   <li>{@code accumPaid >= 0}</li>
 *   <li>{@code accumPaid == Σ postings.amount}</li>
 * </ul>
 * 동시성: DB 어댑터는 이 애그리게잇의 적재→전기→저장을 SELECT FOR UPDATE(또는 낙관적 락)로 직렬화한다.
 */
public final class LimitLedger implements LimitLedgerView {

    /** DTL 1행 — 누적의 근거 (calc_id 단위 가감). */
    public record Posting(long calcId, Money amount) {
    }

    private final long ledgerId;
    private final PolicyNo policyNo;
    private final AgentId agentId;
    private final LocalDate contractDate;
    private final LocalDate fyStart;
    private final LocalDate fyEnd;
    private final BigDecimal limitMultiple;
    private final long ruleVersionId;
    private Money monthlyPremium;
    private Money limitAmount;
    private Money accumPaid;
    private final List<Posting> postings = new ArrayList<>();

    /** 한도액 산출 반올림: 절사(FLOOR) — 한도를 보수적으로 낮게 잡는다 (사규 확인 파라미터). */
    private static final RoundingPolicy LIMIT_ROUNDING = RoundingPolicy.KRW_FLOOR;

    public LimitLedger(long ledgerId, PolicyNo policyNo, AgentId agentId, LocalDate contractDate,
                       LocalDate fyStart, LocalDate fyEnd, Money monthlyPremium,
                       BigDecimal limitMultiple, long ruleVersionId) {
        this.ledgerId = ledgerId;
        this.policyNo = Objects.requireNonNull(policyNo);
        this.agentId = Objects.requireNonNull(agentId);
        this.contractDate = Objects.requireNonNull(contractDate);
        this.fyStart = Objects.requireNonNull(fyStart);
        this.fyEnd = Objects.requireNonNull(fyEnd);
        this.monthlyPremium = Objects.requireNonNull(monthlyPremium);
        this.limitMultiple = Objects.requireNonNull(limitMultiple);
        this.ruleVersionId = ruleVersionId;
        this.limitAmount = monthlyPremium.multiply(limitMultiple, LIMIT_ROUNDING);
        this.accumPaid = Money.ZERO;
    }

    /**
     * DB 재적재(rehydrate) — 저장된 원장 행 + DTL(posting_seq 순)로 애그리게잇을 복원한다.
     * 감액 재산정으로 {@code accum > limit}인 상태도 그대로 복원해야 하므로 {@link #post}의
     * 게이트 검증을 거치지 않는다. 복원 후 {@code accumPaid}가 저장된 값과 다르면
     * 원장 불변식(accum = Σ DTL) 위반이므로 즉시 실패한다.
     */
    public static LimitLedger rehydrate(long ledgerId, PolicyNo policyNo, AgentId agentId,
                                        LocalDate contractDate, LocalDate fyStart, LocalDate fyEnd,
                                        Money monthlyPremium, BigDecimal limitMultiple,
                                        long ruleVersionId, Money storedLimitAmount,
                                        Money storedAccumPaid, List<Posting> storedPostings) {
        LimitLedger ledger = new LimitLedger(ledgerId, policyNo, agentId, contractDate,
                fyStart, fyEnd, monthlyPremium, limitMultiple, ruleVersionId);
        ledger.limitAmount = Objects.requireNonNull(storedLimitAmount);
        Money sum = Money.ZERO;
        for (Posting posting : storedPostings) {
            ledger.postings.add(posting);
            sum = sum.plus(posting.amount());
        }
        if (!sum.equals(storedAccumPaid)) {
            throw new IllegalStateException(
                    "원장 불변식 위반(accum_paid ≠ Σ DTL): 원장=" + policyNo + "/" + agentId
                            + " accum_paid=" + storedAccumPaid + " ΣDTL=" + sum);
        }
        ledger.accumPaid = storedAccumPaid;
        return ledger;
    }

    /** 지급 전기(양수) 또는 환수 차감(음수). 게이트 검증을 통과한 금액만 들어와야 한다. */
    public void post(long calcId, Money amount) {
        Money newAccum = accumPaid.plus(amount);
        if (amount.isPositive() && newAccum.isGreaterThan(limitAmount)) {
            throw new LimitExceededException(
                    "한도 초과 전기 시도: 원장=" + policyNo + "/" + agentId
                            + " 누적=" + accumPaid + " +" + amount + " > 한도=" + limitAmount);
        }
        if (newAccum.isNegative()) {
            throw new IllegalStateException(
                    "누적이 음수가 될 수 없습니다: 원장=" + policyNo + "/" + agentId
                            + " 누적=" + accumPaid + " " + amount);
        }
        postings.add(new Posting(calcId, amount));
        accumPaid = newAccum;
    }

    /** 보험료 변경(감액/증액) → 한도 재산정. 기지급 누적은 유지된다 (§6.1.5). */
    public void reprice(Money newMonthlyPremium) {
        this.monthlyPremium = Objects.requireNonNull(newMonthlyPremium);
        this.limitAmount = newMonthlyPremium.multiply(limitMultiple, LIMIT_ROUNDING);
    }

    /** 초년도 윈도우 내 여부 — 게이트 적용 대상 판정. */
    public boolean inFirstYearWindow(LocalDate date) {
        return !date.isBefore(fyStart) && !date.isAfter(fyEnd);
    }

    @Override
    public Money limitAmount() {
        return limitAmount;
    }

    @Override
    public Money accumPaid() {
        return accumPaid;
    }

    public long ledgerId() {
        return ledgerId;
    }

    public PolicyNo policyNo() {
        return policyNo;
    }

    public AgentId agentId() {
        return agentId;
    }

    public LocalDate contractDate() {
        return contractDate;
    }

    public LocalDate fyStart() {
        return fyStart;
    }

    public LocalDate fyEnd() {
        return fyEnd;
    }

    public Money monthlyPremium() {
        return monthlyPremium;
    }

    public BigDecimal limitMultiple() {
        return limitMultiple;
    }

    public long ruleVersionId() {
        return ruleVersionId;
    }

    public List<Posting> postings() {
        return List.copyOf(postings);
    }

    /** 불변식 검증 — 마감 배치 전수 검증(§7 ④)에서 사용. */
    public boolean invariantHolds() {
        Money sum = postings.stream().map(Posting::amount).reduce(Money.ZERO, Money::plus);
        return sum.equals(accumPaid) && !accumPaid.isNegative();
    }
}
