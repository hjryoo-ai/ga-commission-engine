package ga.comm.calc.net;

import ga.comm.calc.fixture.InMemoryCommCalcStore;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;
import ga.comm.domain.type.RecipientType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 순액 산출 공용 컴포넌트 (설계서 §3.0 합산 규약, 부록 B-4).
 *
 * <p>핵심 회귀: 마감 전 정정으로 원본(REVERSED)·reversal·rebook이 같은 월에 공존할 때
 * 상태 필터 합산(REVERSED 제외)은 원본만 빼고 음수 reversal을 남겨 이중 차감(과소 지급)한다.
 * 전 상태 합산만이 정확하다 — Phase 11에서 실증된 결함의 컴포넌트 레벨 박제.
 */
class NetAmountCalculatorTest {

    private final InMemoryCommCalcStore store = new InMemoryCommCalcStore();
    private final NetAmountCalculator calculator = new NetAmountCalculator(store);

    private CommCalcRecord record(String recipientId, RecipientType type, CommTypeCode commType,
                                  long amount, CalcStatus status, Long reversalOf, String ym) {
        return store.insert(new CommCalcRecord(null, 1L, new PolicyNo("POL-NET-1"), type,
                recipientId, commType, Money.won(Math.abs(amount)), Rate.of("1"),
                Money.won(amount), Money.ZERO, CloseYm.of(ym), status, reversalOf, "[]", "[]"));
    }

    @Test
    @DisplayName("회귀(§3.0): 원본 REVERSED·reversal·rebook 공존 시 순액은 rebook 금액 — 이중 차감 없음")
    void 마감_전_정정_3종_공존은_이중_차감이_없다() {
        CommCalcRecord original = record("A-1001", RecipientType.AGENT, CommTypeCode.FY_COMM,
                1_890_000, CalcStatus.REVERSED, null, "202608");
        record("A-1001", RecipientType.AGENT, CommTypeCode.FY_COMM,
                -1_890_000, CalcStatus.CALCULATED, original.calcId(), "202608");
        record("A-1001", RecipientType.AGENT, CommTypeCode.FY_COMM,
                1_755_000, CalcStatus.CALCULATED, null, "202608");

        // 전 상태 합산: +1,890,000 −1,890,000 +1,755,000 = 1,755,000
        assertThat(calculator.netOf(RecipientType.AGENT, "A-1001", CloseYm.of("202608")))
                .isEqualTo(Money.won(1_755_000));

        // 상태 필터 합산(REVERSED 제외)이었다면 −135,000: 규약 위반의 결과값을 명시적으로 배제
        Money statusFiltered = NetAmountCalculator.netOf(store.all().stream()
                .filter(r -> r.status() != CalcStatus.REVERSED).toList());
        assertThat(statusFiltered).isEqualTo(Money.won(-135_000));
        assertThat(calculator.netOf(RecipientType.AGENT, "A-1001", CloseYm.of("202608")))
                .isNotEqualTo(statusFiltered);
    }

    @Test
    @DisplayName("차원 필터(수급자·유형·마감월)만 허용된다 — 상태는 결코 거르지 않는다")
    void 차원_필터만_적용된다() {
        record("A-1001", RecipientType.AGENT, CommTypeCode.FY_COMM, 100_000, CalcStatus.PAID, null, "202608");
        record("A-1001", RecipientType.AGENT, CommTypeCode.INCENTIVE, 30_000, CalcStatus.CONFIRMED, null, "202608");
        record("A-1001", RecipientType.AGENT, CommTypeCode.FY_COMM, 999_999, CalcStatus.PAID, null, "202609");
        record("A-2002", RecipientType.AGENT, CommTypeCode.FY_COMM, 50_000, CalcStatus.CALCULATED, null, "202608");
        record("T1", RecipientType.ORG, CommTypeCode.OVERRIDE, 5_000, CalcStatus.REVERSED, null, "202608");

        assertThat(calculator.netOf(RecipientType.AGENT, "A-1001", CommTypeCode.FY_COMM, CloseYm.of("202608")))
                .isEqualTo(Money.won(100_000));
        assertThat(calculator.netOf(RecipientType.AGENT, "A-1001", CloseYm.of("202608")))
                .isEqualTo(Money.won(130_000));
        // REVERSED 레코드도 (reversal이 없다면) 그대로 합산에 남는다
        assertThat(calculator.netOf(RecipientType.ORG, "T1", CloseYm.of("202608")))
                .isEqualTo(Money.won(5_000));
    }

    @Test
    @DisplayName("레코드 집합 순액: 환수 음수와 지급 양수가 그대로 상쇄된다")
    void 레코드_집합_순액() {
        List<CommCalcRecord> records = List.of(
                record("A-1001", RecipientType.AGENT, CommTypeCode.FY_COMM, 1_890_000,
                        CalcStatus.CONFIRMED, null, "202609"),
                record("A-1001", RecipientType.AGENT, CommTypeCode.CLAWBACK, -1_323_000,
                        CalcStatus.CONFIRMED, null, "202609"));

        assertThat(NetAmountCalculator.netOf(records)).isEqualTo(Money.won(567_000));
        assertThat(NetAmountCalculator.netOf(List.of())).isEqualTo(Money.ZERO);
    }
}
