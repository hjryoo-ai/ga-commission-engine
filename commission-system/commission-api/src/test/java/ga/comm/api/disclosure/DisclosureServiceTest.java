package ga.comm.api.disclosure;

import ga.comm.calc.fixture.InMemoryCommCalcStore;
import ga.comm.calc.fixture.InMemoryPolicyEventStore;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;
import ga.comm.domain.type.EventType;
import ga.comm.domain.type.RecipientType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판매수수료 비교공시(§1) — 추출 계층이 순액(전 상태 합산)을 정확히 뽑고, <b>서식만 갈아끼우면</b>
 * 같은 집계에서 다른 공시가 나옴을 실증한다. 마감 전 정정(reversal&amp;rebook)에서도 이중 차감이 없다.
 */
class DisclosureServiceTest {

    private InMemoryCommCalcStore calcStore;
    private InMemoryPolicyEventStore eventStore;
    private DisclosureService disclosure;

    private static final AgentId AGENT = new AgentId("A-1001");

    @BeforeEach
    void setUp() {
        calcStore = new InMemoryCommCalcStore();
        eventStore = new InMemoryPolicyEventStore();
        disclosure = new DisclosureService(calcStore, eventStore);

        // SAMLIFE/WL-A: FY_COMM에 마감 전 정정(원본 REVERSED · reversal · rebook 공존) → 순액 900,000
        long e1 = seedEvent("SAMLIFE", "WL-A", "POL-1");
        long original = insert(e1, "POL-1", RecipientType.AGENT, AGENT.value(), CommTypeCode.FY_COMM,
                1_000_000, "202608", CalcStatus.REVERSED, null);
        insert(e1, "POL-1", RecipientType.AGENT, AGENT.value(), CommTypeCode.FY_COMM,
                -1_000_000, "202608", CalcStatus.CALCULATED, original);
        insert(e1, "POL-1", RecipientType.AGENT, AGENT.value(), CommTypeCode.FY_COMM,
                900_000, "202608", CalcStatus.CALCULATED, null);
        insert(e1, "POL-1", RecipientType.AGENT, AGENT.value(), CommTypeCode.INCENTIVE,
                200_000, "202608", CalcStatus.CALCULATED, null);

        // SAMLIFE/WL-B: FY_COMM 500,000
        long e2 = seedEvent("SAMLIFE", "WL-B", "POL-2");
        insert(e2, "POL-2", RecipientType.AGENT, AGENT.value(), CommTypeCode.FY_COMM,
                500_000, "202608", CalcStatus.CALCULATED, null);

        // KYOBO/WL-A: FY_COMM 700,000 (익월)
        long e3 = seedEvent("KYOBO", "WL-A", "POL-3");
        insert(e3, "POL-3", RecipientType.AGENT, AGENT.value(), CommTypeCode.FY_COMM,
                700_000, "202609", CalcStatus.CALCULATED, null);
    }

    private DisclosureAggregate extract() {
        return disclosure.extract(List.of(CloseYm.of("202608"), CloseYm.of("202609")));
    }

    @Test
    void 추출_순액은_전_상태_합산이라_정정에도_이중차감이_없다() {
        DisclosureAggregate agg = extract();

        // SAMLIFE/WL-A/FY_COMM 순액 = +1,000,000 −1,000,000 +900,000 = 900,000 (상태 필터였다면 −100,000)
        Money wlaFy = agg.figures().stream()
                .filter(f -> f.insurerCd().value().equals("SAMLIFE")
                        && f.productKey().value().equals("WL-A")
                        && f.commType().equals(CommTypeCode.FY_COMM))
                .map(DisclosureAggregate.Figure::net).reduce(Money.ZERO, Money::plus);
        assertThat(wlaFy).isEqualTo(Money.won(900_000));
        // 전체 순액 = 900,000 + 200,000 + 500,000 + 700,000
        assertThat(DisclosureFormats.totalNet(agg)).isEqualTo(Money.won(2_300_000));
    }

    @Test
    void 서식_A는_상품별_유형별_피벗으로_매핑한다() {
        DisclosureFormat.FormattedDisclosure out = DisclosureFormats.productByCommType().render(extract());

        assertThat(out.headers()).containsExactly("상품", "FY_COMM", "INCENTIVE", "합계");
        // WL-A: FY_COMM 900,000(SAMLIFE) + 700,000(KYOBO) = 1,600,000, INCENTIVE 200,000, 합계 1,800,000
        assertThat(out.rows()).contains(List.of("WL-A", "1600000", "200000", "1800000"));
        // WL-B: FY_COMM 500,000, INCENTIVE 0, 합계 500,000
        assertThat(out.rows()).contains(List.of("WL-B", "500000", "0", "500000"));
    }

    @Test
    void 서식_B는_보험사별_총계로_매핑한다_추출은_그대로다() {
        DisclosureAggregate agg = extract();
        DisclosureFormat.FormattedDisclosure out = DisclosureFormats.insurerTotal().render(agg);

        assertThat(out.headers()).containsExactly("보험사", "판매수수료순액");
        // SAMLIFE = 900,000 + 200,000 + 500,000 = 1,600,000 / KYOBO = 700,000
        assertThat(out.rows()).contains(List.of("KYOBO", "700000"), List.of("SAMLIFE", "1600000"));
        // 서식을 바꿔도 추출 집계 자체(figure 수)는 동일하다 — 매핑 계층만 다르다
        assertThat(agg.figures()).hasSize(4);
    }

    @Test
    void 비교설명_순위는_FY수수료_기준_등급을_매긴다() {
        List<RankingService.CommissionRank> ranks =
                new RankingService().rank(extract(), CommTypeCode.FY_COMM);

        // (보험사×상품) FY 순액: SAMLIFE/WL-A 900,000 > KYOBO/WL-A 700,000 > SAMLIFE/WL-B 500,000
        assertThat(ranks).extracting(r -> r.insurerCd().value() + "/" + r.productKey().value())
                .containsExactly("SAMLIFE/WL-A", "KYOBO/WL-A", "SAMLIFE/WL-B");
        assertThat(ranks).extracting(RankingService.CommissionRank::rank).containsExactly(1, 2, 3);
        assertThat(ranks).extracting(RankingService.CommissionRank::grade).containsExactly("A", "B", "C");
        assertThat(ranks.get(0).net()).isEqualTo(Money.won(900_000));
    }

    @Test
    void 등급_정책은_교체_가능하다_순위_로직은_그대로다() {
        // 절대 임계 정책(800,000 이상 A, 아니면 C)으로 갈아끼우면 3분위와 다른 등급이 나온다
        GradingPolicy absolute = (rank, total, value) -> value.toLong() >= 800_000 ? "A" : "C";
        List<RankingService.CommissionRank> ranks =
                new RankingService(absolute).rank(extract(), CommTypeCode.FY_COMM);

        // 순위 순서는 정책과 무관하게 동일, 등급만 달라진다 (900k→A, 700k→C, 500k→C)
        assertThat(ranks).extracting(RankingService.CommissionRank::rank).containsExactly(1, 2, 3);
        assertThat(ranks).extracting(RankingService.CommissionRank::grade).containsExactly("A", "C", "C");
    }

    // ---- 시드 헬퍼 ----

    private long seedEvent(String insurer, String product, String policyNo) {
        PolicyEvent event = new PolicyEvent(null, insurer + ":" + policyNo + ":NEW",
                new PolicyNo(policyNo), new InsurerCode(insurer), new ProductKey(product),
                EventType.NEW, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), AGENT,
                Money.won(300_000), null, null, Map.of());
        return eventStore.upsertByKey(event).event().eventId();
    }

    private long insert(long eventId, String policyNo, RecipientType type, String recipientId,
                        CommTypeCode commType, long amount, String closeYm, CalcStatus status,
                        Long reversalOf) {
        return calcStore.insert(new CommCalcRecord(null, eventId, new PolicyNo(policyNo), type,
                recipientId, commType, Money.won(Math.abs(amount)), Rate.of("1"),
                Money.won(amount), Money.ZERO, CloseYm.of(closeYm), status, reversalOf, "[]", "[]"))
                .calcId();
    }
}
