package ga.comm.rule.fixture;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Rate;
import ga.comm.domain.money.RoundingPolicy;
import ga.comm.domain.type.Direction;
import ga.comm.domain.type.EventType;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 표준 테스트 픽스처 — 설계서 부록 A(2026-08-01 체결, 월납 30만, 지급률 90%) 시나리오와
 * 규제 로드맵(1200%룰 2026-07-01, 분급 2027-01-01)을 반영한 룰 세트.
 *
 * <p>요율·환수율은 전부 예시값이다. 골든 케이스의 기대값은 이 픽스처 기준으로 산출된다.
 */
public final class RuleFixtures {

    public static final InsurerCode INSURER = new InsurerCode("SAMLIFE");
    public static final ProductKey PRODUCT = new ProductKey("WHOLE-LIFE-20Y");
    public static final String GRADE_SENIOR = "SR";   // 지급률 90%
    public static final String GRADE_JUNIOR = "JR";   // 지급률 70%

    public static final LocalDate RULES_FROM = LocalDate.of(2026, 1, 1);
    public static final LocalDate GA_LIMIT_FROM = LocalDate.of(2026, 7, 1);
    public static final LocalDate DEFERRAL_FROM = LocalDate.of(2027, 1, 1);

    private RuleFixtures() {
    }

    /** 2026~2027 표준 룰 일체가 적재된 저장소. */
    public static InMemoryRuleStore standardRules() {
        InMemoryRuleStore store = new InMemoryRuleStore();
        long id = 1000;

        // 수수료 유형 속성 (설계서 §2.2 표: 한도 포함 여부 / 환수 대상 여부)
        store.addCommTypeAttr(attr(CommTypeCode.FY_COMM, true, true));
        store.addCommTypeAttr(attr(CommTypeCode.RENEWAL, false, false));
        store.addCommTypeAttr(attr(CommTypeCode.INCENTIVE, true, true));
        store.addCommTypeAttr(attr(CommTypeCode.SETTLEMENT_SUPPORT, true, true));
        store.addCommTypeAttr(attr(CommTypeCode.OVERRIDE, false, false));
        store.addCommTypeAttr(attr(CommTypeCode.DEFERRED, false, false));
        store.addCommTypeAttr(attr(CommTypeCode.CLAWBACK, false, false));

        // OUTBOUND 초년도 모집수수료: 신계약 성립(회차 null) 700%, 2~12회차 각 15%
        store.addActiveRate(rate(++id, CommTypeCode.FY_COMM, null, "7.0"));
        for (int inst = 2; inst <= 12; inst++) {
            store.addActiveRate(rate(++id, CommTypeCode.FY_COMM, inst, "0.15"));
        }
        // 계속수수료(2차년도~): 회차 무관 2%
        for (int inst = 13; inst <= 24; inst++) {
            store.addActiveRate(rate(++id, CommTypeCode.RENEWAL, inst, "0.02"));
        }

        // 등급별 지급률
        store.addPayoutRate(payout(GRADE_SENIOR, CommTypeCode.FY_COMM, "0.9"));
        store.addPayoutRate(payout(GRADE_SENIOR, CommTypeCode.RENEWAL, "0.9"));
        store.addPayoutRate(payout(GRADE_SENIOR, CommTypeCode.INCENTIVE, "1.0"));
        store.addPayoutRate(payout(GRADE_JUNIOR, CommTypeCode.FY_COMM, "0.7"));
        store.addPayoutRate(payout(GRADE_JUNIOR, CommTypeCode.RENEWAL, "0.7"));
        store.addPayoutRate(payout(GRADE_JUNIOR, CommTypeCode.INCENTIVE, "1.0"));

        // 1200%룰: GA→설계사 2026-07-01 체결분부터, 초년도 12개월, 초과분 이연, 환수 시 복원
        store.addLimitRule(new LimitRule(1, ChannelType.GA_TO_AGENT, new BigDecimal("12.00"),
                12, OverLimitAction.DEFER_AFTER_FY, true,
                EffectivePeriod.from(GA_LIMIT_FROM)));

        // 분급 커브 — 커브 자체가 데이터: 2027 체결분 4년, 2029 체결분 7년 (로드맵 §1)
        store.addDeferralCurve(new DeferralCurve(1, "4년 분급(2027)",
                EffectivePeriod.of(DEFERRAL_FROM, LocalDate.of(2028, 12, 31)),
                List.of(
                        new DeferralCurve.CurvePoint(0, Rate.of("0.4")),
                        new DeferralCurve.CurvePoint(12, Rate.of("0.2")),
                        new DeferralCurve.CurvePoint(24, Rate.of("0.2")),
                        new DeferralCurve.CurvePoint(36, Rate.of("0.2"))
                )));
        store.addDeferralCurve(new DeferralCurve(2, "7년 분급(2029)",
                EffectivePeriod.from(LocalDate.of(2029, 1, 1)),
                List.of(
                        new DeferralCurve.CurvePoint(0, Rate.of("0.3")),
                        new DeferralCurve.CurvePoint(12, Rate.of("0.1")),
                        new DeferralCurve.CurvePoint(24, Rate.of("0.1")),
                        new DeferralCurve.CurvePoint(36, Rate.of("0.1")),
                        new DeferralCurve.CurvePoint(48, Rate.of("0.1")),
                        new DeferralCurve.CurvePoint(60, Rate.of("0.1")),
                        new DeferralCurve.CurvePoint(72, Rate.of("0.1")),
                        new DeferralCurve.CurvePoint(84, Rate.of("0.1"))
                )));

        // 환수율: 전 상품 공통 — 해약/실효 시 12회차 이하 잔여기간 비례(예시로 구간 단순화)
        for (EventType et : List.of(EventType.CANCEL, EventType.LAPSE)) {
            store.addClawbackTable(new ClawbackTable(null, et, EffectivePeriod.from(RULES_FROM),
                    List.of(
                            new ClawbackTable.Band(0, 6, Rate.of("0.7")),
                            new ClawbackTable.Band(7, 12, Rate.of("0.4"))
                    )));
        }
        // 청약철회: 전액 환수
        store.addClawbackTable(new ClawbackTable(null, EventType.WITHDRAW, EffectivePeriod.from(RULES_FROM),
                List.of(new ClawbackTable.Band(0, 999, Rate.of("1.0")))));

        // 조직 오버라이드: FY_COMM에 대해 팀 5%, 지점 3%, 본부 2%
        store.addOrgOverrideRate(new OrgOverrideRate(OrgLevel.TEAM, CommTypeCode.FY_COMM,
                Rate.of("0.05"), EffectivePeriod.from(RULES_FROM)));
        store.addOrgOverrideRate(new OrgOverrideRate(OrgLevel.BRANCH, CommTypeCode.FY_COMM,
                Rate.of("0.03"), EffectivePeriod.from(RULES_FROM)));
        store.addOrgOverrideRate(new OrgOverrideRate(OrgLevel.HQ, CommTypeCode.FY_COMM,
                Rate.of("0.02"), EffectivePeriod.from(RULES_FROM)));

        return store;
    }

    public static CommRateRule rate(long id, CommTypeCode type, Integer installmentNo, String rateValue) {
        return new CommRateRule(id, Direction.OUTBOUND, INSURER, PRODUCT, type, installmentNo,
                Rate.of(rateValue), EffectivePeriod.from(RULES_FROM), 1, RateStatus.ACTIVE);
    }

    private static PayoutRateRule payout(String grade, CommTypeCode type, String rateValue) {
        return new PayoutRateRule(grade, type, Rate.of(rateValue), EffectivePeriod.from(RULES_FROM));
    }

    private static CommTypeAttr attr(CommTypeCode type, boolean limitIncluded, boolean clawbackTarget) {
        return new CommTypeAttr(type, limitIncluded, RoundingPolicy.KRW_FLOOR, clawbackTarget,
                EffectivePeriod.from(RULES_FROM));
    }
}
