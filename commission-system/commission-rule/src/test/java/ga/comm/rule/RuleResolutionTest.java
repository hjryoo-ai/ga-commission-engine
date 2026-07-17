package ga.comm.rule;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Rate;
import ga.comm.domain.type.Direction;
import ga.comm.domain.type.EventType;
import ga.comm.rule.fixture.InMemoryRuleStore;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.rule.model.ChannelType;
import ga.comm.rule.model.CommRateRule;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.RateStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static ga.comm.rule.fixture.RuleFixtures.INSURER;
import static ga.comm.rule.fixture.RuleFixtures.PRODUCT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleResolutionTest {

    @Test
    void 유효기간_경계_apply_from_당일부터_적용된다() {
        InMemoryRuleStore store = new InMemoryRuleStore();
        store.addActiveRate(new CommRateRule(1, Direction.OUTBOUND, INSURER, PRODUCT,
                CommTypeCode.FY_COMM, null, Rate.of("7.0"),
                EffectivePeriod.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 31)), 1, RateStatus.ACTIVE));
        store.addActiveRate(new CommRateRule(2, Direction.OUTBOUND, INSURER, PRODUCT,
                CommTypeCode.FY_COMM, null, Rate.of("6.5"),
                EffectivePeriod.from(LocalDate.of(2026, 9, 1)), 2, RateStatus.ACTIVE));

        // 8/31까지는 구버전 7.0
        assertThat(store.findRate(Direction.OUTBOUND, INSURER, PRODUCT, CommTypeCode.FY_COMM, null,
                LocalDate.of(2026, 8, 31)).orElseThrow().rate()).isEqualTo(Rate.of("7.0"));
        // 9/1 당일부터 신버전 6.5
        assertThat(store.findRate(Direction.OUTBOUND, INSURER, PRODUCT, CommTypeCode.FY_COMM, null,
                LocalDate.of(2026, 9, 1)).orElseThrow().rate()).isEqualTo(Rate.of("6.5"));
    }

    @Test
    void DRAFT와_SUPERSEDED는_조회되지_않는다() {
        InMemoryRuleStore store = new InMemoryRuleStore();
        store.insert(new CommRateRule(1, Direction.OUTBOUND, INSURER, PRODUCT,
                CommTypeCode.FY_COMM, null, Rate.of("7.0"),
                EffectivePeriod.from(LocalDate.of(2026, 1, 1)), 1, RateStatus.DRAFT));
        store.insert(new CommRateRule(2, Direction.OUTBOUND, INSURER, PRODUCT,
                CommTypeCode.INCENTIVE, null, Rate.of("1.0"),
                EffectivePeriod.from(LocalDate.of(2026, 1, 1)), 1, RateStatus.SUPERSEDED));

        assertThat(store.findRate(Direction.OUTBOUND, INSURER, PRODUCT, CommTypeCode.FY_COMM, null,
                LocalDate.of(2026, 6, 1))).isEmpty();
        assertThat(store.findRate(Direction.OUTBOUND, INSURER, PRODUCT, CommTypeCode.INCENTIVE, null,
                LocalDate.of(2026, 6, 1))).isEmpty();
    }

    @Test
    void 겹치는_ACTIVE_버전이_2건이면_정합성_오류다() {
        InMemoryRuleStore store = new InMemoryRuleStore();
        store.addActiveRate(RuleFixtures.rate(1, CommTypeCode.FY_COMM, null, "7.0"));
        store.addActiveRate(RuleFixtures.rate(2, CommTypeCode.FY_COMM, null, "6.0"));

        assertThatThrownBy(() -> store.findRate(Direction.OUTBOUND, INSURER, PRODUCT,
                CommTypeCode.FY_COMM, null, LocalDate.of(2026, 6, 1)))
                .isInstanceOf(AmbiguousRuleException.class);
    }

    @Test
    void 한도룰은_2026_07_01_체결분부터_적용된다() {
        InMemoryRuleStore store = RuleFixtures.standardRules();

        // 6/30 체결 계약 → GA 한도룰 미적용 (설계서 §6.1: 이 분기 자체가 룰 데이터)
        assertThat(store.findLimitRule(ChannelType.GA_TO_AGENT, LocalDate.of(2026, 6, 30))).isEmpty();
        // 7/1 체결 계약 → 12배 한도
        assertThat(store.findLimitRule(ChannelType.GA_TO_AGENT, LocalDate.of(2026, 7, 1)))
                .hasValueSatisfying(rule ->
                        assertThat(rule.limitMultiple()).isEqualByComparingTo("12.00"));
    }

    @Test
    void 분급커브는_2027_01_01_체결분부터_적용된다() {
        InMemoryRuleStore store = RuleFixtures.standardRules();
        assertThat(store.findDeferralCurve(LocalDate.of(2026, 12, 31))).isEmpty();
        assertThat(store.findDeferralCurve(LocalDate.of(2027, 1, 1))).isPresent();
    }

    @Test
    void 환수테이블은_상품별_룰이_없으면_공통으로_폴백한다() {
        InMemoryRuleStore store = RuleFixtures.standardRules();
        assertThat(store.findClawbackTable(new ProductKey("UNKNOWN-PRODUCT"), EventType.CANCEL,
                LocalDate.of(2026, 8, 1)))
                .hasValueSatisfying(t -> assertThat(t.productKey()).isNull());
    }

    @Test
    void 한도포함여부는_기준일_유효_버전으로_결정된다() {
        InMemoryRuleStore store = RuleFixtures.standardRules();
        assertThat(store.findCommTypeAttr(CommTypeCode.INCENTIVE, LocalDate.of(2026, 8, 1))
                .orElseThrow().limitIncluded()).isTrue();
        assertThat(store.findCommTypeAttr(CommTypeCode.RENEWAL, LocalDate.of(2026, 8, 1))
                .orElseThrow().limitIncluded()).isFalse();
    }
}
