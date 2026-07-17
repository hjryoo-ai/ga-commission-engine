package ga.comm.shadow;

import ga.comm.calc.fixture.InMemoryCommCalcStore;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;
import ga.comm.domain.type.RecipientType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 섀도 런 (§8.5) — 의도적 차이 주입 시나리오에서 유형별로 정확히 분류되는지, 자체 순액이
 * NetAmountCalculator(전 상태 합산)로 산출되는지 검증한다.
 */
class ShadowRunServiceTest {

    private InMemoryCommCalcStore calcStore;
    private ShadowRunService shadow;
    private static final String A = "A-1001";
    private static final CloseYm AUG = CloseYm.of("202608");
    private static final CloseYm SEP = CloseYm.of("202609");

    @BeforeEach
    void setUp() {
        calcStore = new InMemoryCommCalcStore();
        shadow = new ShadowRunService(calcStore);
    }

    @Test
    void 의도적_차이_주입은_유형별로_정확히_분류된다() {
        // 자체 계산 (내부)
        internal(CommTypeCode.FY_COMM, AUG, 1_890_000);            // = 외부 → MATCH
        internal(CommTypeCode.INCENTIVE, AUG, 500_000);            // 외부 500,003 → ROUNDING(±10)
        internal(CommTypeCode.RENEWAL, AUG, 100_000);             // 외부 90,000 → RATE_DIFF
        internal(CommTypeCode.OVERRIDE, AUG, 30_000);             // 외부 없음 → UNEXPECTED
        internal(CommTypeCode.DEFERRED, SEP, 378_000);           // 외부는 8월에 → TIMING

        List<ExternalSettlementRow> external = List.of(
                ext(CommTypeCode.FY_COMM, AUG, 1_890_000),
                ext(CommTypeCode.INCENTIVE, AUG, 500_003),
                ext(CommTypeCode.RENEWAL, AUG, 90_000),
                ext(CommTypeCode.SETTLEMENT_SUPPORT, AUG, 50_000),  // 내부 없음 → MISSING
                ext(CommTypeCode.DEFERRED, AUG, 378_000));         // 내부는 9월에 → TIMING

        ShadowRunService.ShadowReport report = shadow.compare(external, List.of(AUG, SEP), 10);

        assertThat(report.byType(ShadowDiffType.MATCH)).extracting(d -> d.commType().value())
                .containsExactly("FY_COMM");
        assertThat(report.byType(ShadowDiffType.ROUNDING)).extracting(d -> d.commType().value())
                .containsExactly("INCENTIVE");
        assertThat(report.byType(ShadowDiffType.RATE_DIFF)).extracting(d -> d.commType().value())
                .containsExactly("RENEWAL");
        assertThat(report.byType(ShadowDiffType.MISSING)).extracting(d -> d.commType().value())
                .containsExactly("SETTLEMENT_SUPPORT");
        assertThat(report.byType(ShadowDiffType.UNEXPECTED)).extracting(d -> d.commType().value())
                .containsExactly("OVERRIDE");
        // DEFERRED 8월(MISSING)+9월(UNEXPECTED)이 상쇄 → 둘 다 TIMING
        assertThat(report.byType(ShadowDiffType.TIMING)).hasSize(2)
                .allSatisfy(d -> assertThat(d.commType().value()).isEqualTo("DEFERRED"));

        assertThat(report.clean()).isFalse();
        assertThat(report.summary()).containsEntry(ShadowDiffType.MATCH, 1)
                .containsEntry(ShadowDiffType.TIMING, 2);
    }

    @Test
    void 마감_전_정정에도_자체_순액은_전_상태_합산이다() {
        // 원본 REVERSED 1,890,000 + reversal −1,890,000 + rebook 1,755,000 → 순액 1,755,000
        long orig = insert(CommTypeCode.FY_COMM, AUG, 1_890_000, CalcStatus.REVERSED, null);
        insert(CommTypeCode.FY_COMM, AUG, -1_890_000, CalcStatus.CALCULATED, orig);
        insert(CommTypeCode.FY_COMM, AUG, 1_755_000, CalcStatus.CALCULATED, null);

        // 외부가 rebook 금액과 같으면 MATCH (상태 필터 합산이었다면 순액이 어긋나 오분류됐을 것)
        ShadowRunService.ShadowReport report =
                shadow.compare(List.of(ext(CommTypeCode.FY_COMM, AUG, 1_755_000)), List.of(AUG), 0);
        assertThat(report.clean()).isTrue();
    }

    @Test
    void CSV_어댑터는_외부_정산을_읽고_이상행은_실패한다() {
        String csv = "수급유형,수급자ID,수수료유형,마감월,금액\n"
                + "AGENT,A-1001,FY_COMM,202608,1890000\n";
        List<ExternalSettlementRow> rows = new ShadowCsvAdapter().parse(csv);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).amount()).isEqualTo(Money.won(1_890_000));

        // 열 수 부족 → 조용히 건너뛰지 않고 실패
        assertThatThrownBy(() -> new ShadowCsvAdapter().parse(
                "수급유형,수급자ID,수수료유형,마감월,금액\nAGENT,A-1001,FY_COMM\n"))
                .isInstanceOf(ShadowCsvAdapter.ShadowParseException.class)
                .hasMessageContaining("2행");
    }

    // ---- 헬퍼 ----

    private void internal(CommTypeCode commType, CloseYm ym, long amount) {
        insert(commType, ym, amount, CalcStatus.CALCULATED, null);
    }

    private long insert(CommTypeCode commType, CloseYm ym, long amount, CalcStatus status, Long reversalOf) {
        return calcStore.insert(new CommCalcRecord(null, 1L, new PolicyNo("POL-SHADOW"),
                RecipientType.AGENT, A, commType, Money.won(Math.abs(amount)), Rate.of("1"),
                Money.won(amount), Money.ZERO, ym, status, reversalOf, "[]", "[]")).calcId();
    }

    private ExternalSettlementRow ext(CommTypeCode commType, CloseYm ym, long amount) {
        return new ExternalSettlementRow(RecipientType.AGENT, A, commType, ym, Money.won(amount));
    }
}
