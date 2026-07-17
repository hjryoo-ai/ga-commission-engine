package ga.comm.rule.contract;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Rate;
import ga.comm.domain.money.RoundingPolicy;
import ga.comm.domain.type.Direction;
import ga.comm.domain.type.EventType;
import ga.comm.rule.AmbiguousRuleException;
import ga.comm.rule.RuleRepository;
import ga.comm.rule.model.ChannelType;
import ga.comm.rule.model.ClawbackTable;
import ga.comm.rule.model.CommRateRule;
import ga.comm.rule.model.CommTypeAttr;
import ga.comm.rule.model.DeferralCurve;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.LimitRule;
import ga.comm.rule.model.OrgLevel;
import ga.comm.rule.model.OrgOverrideRate;
import ga.comm.rule.model.OverLimitAction;
import ga.comm.rule.model.PayoutRateRule;
import ga.comm.rule.model.RateStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RuleRepository 계약 테스트 (설계서 §8.7, Phase 10b) — 인메모리 레퍼런스와 Oracle 어댑터의
 * 동작 동등성. 핵심은 <b>유효기간 경계 시맨틱의 박제</b>: 신 버전은 apply_from 당일부터,
 * 구 버전은 그 전날까지 — 두 구현이 반드시 같은 답을 내야 한다.
 */
public abstract class RuleRepositoryContract {

    protected static final InsurerCode INSURER = new InsurerCode("SAMLIFE");
    protected static final ProductKey PRODUCT = new ProductKey("WL-20Y");
    protected static final LocalDate SWITCH_DATE = LocalDate.of(2026, 9, 1);

    protected abstract RuleRepository repository();

    protected abstract RuleSeeder seeder();

    protected <T> T inTx(Supplier<T> work) {
        return work.get();
    }

    private CommRateRule rate(long rateId, Integer installmentNo, String rateValue,
                              EffectivePeriod period, RateStatus status) {
        return new CommRateRule(rateId, Direction.OUTBOUND, INSURER, PRODUCT, CommTypeCode.FY_COMM,
                installmentNo, Rate.of(rateValue), period, 1, status);
    }

    @Test
    void 요율은_apply_from_당일부터_신_버전이_적용되고_구_버전은_전날까지다() {
        seeder().rate(rate(9001, null, "7.0",
                EffectivePeriod.of(LocalDate.of(2026, 1, 1), SWITCH_DATE.minusDays(1)), RateStatus.ACTIVE));
        seeder().rate(rate(9002, null, "8.0",
                EffectivePeriod.from(SWITCH_DATE), RateStatus.ACTIVE));

        CommRateRule dayBefore = inTx(() -> repository().findRate(Direction.OUTBOUND, INSURER, PRODUCT,
                CommTypeCode.FY_COMM, null, SWITCH_DATE.minusDays(1))).orElseThrow();
        CommRateRule onSwitchDay = inTx(() -> repository().findRate(Direction.OUTBOUND, INSURER, PRODUCT,
                CommTypeCode.FY_COMM, null, SWITCH_DATE)).orElseThrow();

        assertThat(dayBefore.rateId()).isEqualTo(9001);
        assertThat(dayBefore.rate()).isEqualTo(Rate.of("7.0"));
        assertThat(onSwitchDay.rateId()).isEqualTo(9002);
        assertThat(onSwitchDay.rate()).isEqualTo(Rate.of("8.0"));
    }

    @Test
    void 회차별_요율과_일시지급형_요율은_서로_다른_키다() {
        seeder().rate(rate(9011, null, "7.0", EffectivePeriod.from(LocalDate.of(2026, 1, 1)), RateStatus.ACTIVE));
        seeder().rate(rate(9012, 2, "0.15", EffectivePeriod.from(LocalDate.of(2026, 1, 1)), RateStatus.ACTIVE));

        LocalDate base = LocalDate.of(2026, 8, 1);
        assertThat(inTx(() -> repository().findRate(Direction.OUTBOUND, INSURER, PRODUCT,
                CommTypeCode.FY_COMM, null, base)).orElseThrow().rateId()).isEqualTo(9011);
        assertThat(inTx(() -> repository().findRate(Direction.OUTBOUND, INSURER, PRODUCT,
                CommTypeCode.FY_COMM, 2, base)).orElseThrow().rateId()).isEqualTo(9012);
        assertThat(inTx(() -> repository().findRate(Direction.OUTBOUND, INSURER, PRODUCT,
                CommTypeCode.FY_COMM, 3, base))).isEmpty();
    }

    @Test
    void ACTIVE가_아닌_버전은_조회되지_않는다() {
        seeder().rate(rate(9021, null, "7.0", EffectivePeriod.from(LocalDate.of(2026, 1, 1)), RateStatus.DRAFT));
        seeder().rate(rate(9022, null, "6.0", EffectivePeriod.from(LocalDate.of(2026, 1, 1)), RateStatus.SUPERSEDED));

        assertThat(inTx(() -> repository().findRate(Direction.OUTBOUND, INSURER, PRODUCT,
                CommTypeCode.FY_COMM, null, LocalDate.of(2026, 8, 1)))).isEmpty();
    }

    @Test
    void 같은_키에_겹치는_ACTIVE가_있으면_Ambiguous로_실패한다() {
        // 데이터 정합성 오류는 침묵 선택이 아니라 명시적 실패다 (fail-fast, 부록 B-13)
        seeder().rate(rate(9031, null, "7.0", EffectivePeriod.from(LocalDate.of(2026, 1, 1)), RateStatus.ACTIVE));
        seeder().rate(rate(9032, null, "8.0", EffectivePeriod.from(LocalDate.of(2026, 6, 1)), RateStatus.ACTIVE));

        assertThatThrownBy(() -> inTx(() -> repository().findRate(Direction.OUTBOUND, INSURER, PRODUCT,
                CommTypeCode.FY_COMM, null, LocalDate.of(2026, 8, 1))))
                .isInstanceOf(AmbiguousRuleException.class);
    }

    @Test
    void 수수료유형_속성은_유효기간_버전으로_교체된다() {
        // 시책의 한도 합산: 2026-07-01부터 포함 — 시행일 분기가 데이터로 표현된다 (§1)
        seeder().commTypeAttr(new CommTypeAttr(CommTypeCode.INCENTIVE, false, RoundingPolicy.KRW_FLOOR,
                true, EffectivePeriod.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30))));
        seeder().commTypeAttr(new CommTypeAttr(CommTypeCode.INCENTIVE, true, RoundingPolicy.KRW_FLOOR,
                true, EffectivePeriod.from(LocalDate.of(2026, 7, 1))));

        assertThat(inTx(() -> repository().findCommTypeAttr(CommTypeCode.INCENTIVE, LocalDate.of(2026, 6, 30)))
                .orElseThrow().limitIncluded()).isFalse();
        assertThat(inTx(() -> repository().findCommTypeAttr(CommTypeCode.INCENTIVE, LocalDate.of(2026, 7, 1)))
                .orElseThrow().limitIncluded()).isTrue();
    }

    @Test
    void 지급률은_등급과_유형과_기준일로_조회된다() {
        seeder().payoutRate(new PayoutRateRule("SR", CommTypeCode.FY_COMM, Rate.of("0.9"),
                EffectivePeriod.of(LocalDate.of(2026, 1, 1), SWITCH_DATE.minusDays(1))));
        seeder().payoutRate(new PayoutRateRule("SR", CommTypeCode.FY_COMM, Rate.of("0.92"),
                EffectivePeriod.from(SWITCH_DATE)));

        assertThat(inTx(() -> repository().findPayoutRate("SR", CommTypeCode.FY_COMM, SWITCH_DATE.minusDays(1)))
                .orElseThrow().payoutRate()).isEqualTo(Rate.of("0.9"));
        assertThat(inTx(() -> repository().findPayoutRate("SR", CommTypeCode.FY_COMM, SWITCH_DATE))
                .orElseThrow().payoutRate()).isEqualTo(Rate.of("0.92"));
        assertThat(inTx(() -> repository().findPayoutRate("JR", CommTypeCode.FY_COMM, SWITCH_DATE))).isEmpty();
    }

    @Test
    void 한도룰은_계약체결일_기준이고_시행_전_계약은_미적용이다() {
        seeder().limitRule(new LimitRule(8001, ChannelType.GA_TO_AGENT, new BigDecimal("12.00"), 12,
                OverLimitAction.DEFER_AFTER_FY, true, EffectivePeriod.from(LocalDate.of(2026, 7, 1))));

        assertThat(inTx(() -> repository().findLimitRule(ChannelType.GA_TO_AGENT, LocalDate.of(2026, 6, 30))))
                .as("2026-06-30 체결 계약은 GA 확대 적용 대상이 아니다 — 룰 부재 = 미적용")
                .isEmpty();

        LimitRule applied = inTx(() -> repository().findLimitRule(ChannelType.GA_TO_AGENT, LocalDate.of(2026, 7, 1)))
                .orElseThrow();
        assertThat(applied.ruleId()).isEqualTo(8001);
        assertThat(applied.limitMultiple()).isEqualByComparingTo(new BigDecimal("12.00"));
        assertThat(applied.fyWindowMonths()).isEqualTo(12);
        assertThat(applied.overLimitAction()).isEqualTo(OverLimitAction.DEFER_AFTER_FY);
        assertThat(applied.clawbackRestores()).isTrue();
        assertThat(inTx(() -> repository().findLimitRule(ChannelType.CAPTIVE, LocalDate.of(2026, 7, 1))))
                .isEmpty();
    }

    @Test
    void 분급커브는_계약체결일_기준_버전이고_포인트는_경과월_순으로_보존된다() {
        seeder().deferralCurve(new DeferralCurve(4001, "4년 분급", EffectivePeriod.from(LocalDate.of(2027, 1, 1)),
                List.of(new DeferralCurve.CurvePoint(0, Rate.of("0.4")),
                        new DeferralCurve.CurvePoint(12, Rate.of("0.2")),
                        new DeferralCurve.CurvePoint(24, Rate.of("0.2")),
                        new DeferralCurve.CurvePoint(36, Rate.of("0.2")))));

        assertThat(inTx(() -> repository().findDeferralCurve(LocalDate.of(2026, 12, 31)))).isEmpty();

        DeferralCurve curve = inTx(() -> repository().findDeferralCurve(LocalDate.of(2027, 1, 1))).orElseThrow();
        assertThat(curve.curveId()).isEqualTo(4001);
        assertThat(curve.points()).extracting(DeferralCurve.CurvePoint::monthNo)
                .containsExactly(0, 12, 24, 36);
        assertThat(curve.points().get(0).pct()).isEqualTo(Rate.of("0.4"));
    }

    @Test
    void 환수테이블은_상품별이_없으면_전상품_공통으로_폴백한다() {
        seeder().clawbackTable(new ClawbackTable(null, EventType.CANCEL,
                EffectivePeriod.from(LocalDate.of(2026, 1, 1)),
                List.of(new ClawbackTable.Band(0, 6, Rate.of("0.7")),
                        new ClawbackTable.Band(7, 12, Rate.of("0.4")))));
        seeder().clawbackTable(new ClawbackTable(PRODUCT.value(), EventType.CANCEL,
                EffectivePeriod.from(LocalDate.of(2026, 1, 1)),
                List.of(new ClawbackTable.Band(0, 12, Rate.of("0.5")))));

        LocalDate contract = LocalDate.of(2026, 8, 1);
        ClawbackTable specific = inTx(() -> repository().findClawbackTable(PRODUCT, EventType.CANCEL, contract))
                .orElseThrow();
        assertThat(specific.productKey()).isEqualTo(PRODUCT.value());
        assertThat(specific.rateFor(5)).contains(Rate.of("0.5"));

        ClawbackTable fallback = inTx(() -> repository().findClawbackTable(
                new ProductKey("OTHER-PRD"), EventType.CANCEL, contract)).orElseThrow();
        assertThat(fallback.productKey()).isNull();
        assertThat(fallback.rateFor(5)).contains(Rate.of("0.7"));
        assertThat(fallback.rateFor(10)).contains(Rate.of("0.4"));
        assertThat(fallback.rateFor(13)).isEmpty();

        assertThat(inTx(() -> repository().findClawbackTable(PRODUCT, EventType.LAPSE, contract))).isEmpty();
    }

    @Test
    void 조직_오버라이드율은_계층과_유형과_기준일로_조회된다() {
        seeder().orgOverrideRate(new OrgOverrideRate(OrgLevel.TEAM, CommTypeCode.FY_COMM, Rate.of("0.05"),
                EffectivePeriod.from(LocalDate.of(2026, 1, 1))));

        assertThat(inTx(() -> repository().findOrgOverrideRate(OrgLevel.TEAM, CommTypeCode.FY_COMM,
                LocalDate.of(2026, 8, 1))).orElseThrow().overrideRate()).isEqualTo(Rate.of("0.05"));
        assertThat(inTx(() -> repository().findOrgOverrideRate(OrgLevel.BRANCH, CommTypeCode.FY_COMM,
                LocalDate.of(2026, 8, 1)))).isEmpty();
        assertThat(inTx(() -> repository().findOrgOverrideRate(OrgLevel.TEAM, CommTypeCode.FY_COMM,
                LocalDate.of(2025, 12, 31)))).isEmpty();
    }

    @Test
    void 기준일_없는_룰_조회_API는_구현체에_존재하지_않는다() {
        // 부록 B-3: "현재 유효" 조회 금지. 위반의 형태는 "업무 키로 단건을 선택하면서
        // 기준일을 받지 않는 find API"다. ID 단건 조회(findById)와 전체 버전 이력
        // (findByKey → List)는 선택이 아니라 식별/감사 조회이므로 허용.
        List<Method> violations = Arrays.stream(repository().getClass().getMethods())
                .filter(m -> !m.isSynthetic() && m.getDeclaringClass() != Object.class)
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> m.getName().startsWith("find"))
                .filter(RuleRepositoryContract::selectsSingleRule)
                .filter(m -> Arrays.stream(m.getParameterTypes()).noneMatch(p -> p == LocalDate.class))
                .filter(m -> Arrays.stream(m.getParameterTypes())
                        .noneMatch(p -> p == long.class || p == Long.class))
                .toList();

        assertThat(violations)
                .as("기준일(LocalDate) 없이 업무 키로 룰을 선택하는 공개 API — 부록 B-3 위반")
                .isEmpty();
    }

    private static boolean selectsSingleRule(Method method) {
        Class<?> returnType = method.getReturnType();
        return returnType == Optional.class
                || returnType.getPackageName().equals("ga.comm.rule.model");
    }
}
