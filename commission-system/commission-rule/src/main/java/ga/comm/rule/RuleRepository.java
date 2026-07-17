package ga.comm.rule;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.type.Direction;
import ga.comm.domain.type.EventType;
import ga.comm.rule.model.ChannelType;
import ga.comm.rule.model.ClawbackTable;
import ga.comm.rule.model.CommRateRule;
import ga.comm.rule.model.CommTypeAttr;
import ga.comm.rule.model.DeferralCurve;
import ga.comm.rule.model.LimitRule;
import ga.comm.rule.model.OrgLevel;
import ga.comm.rule.model.OrgOverrideRate;
import ga.comm.rule.model.PayoutRateRule;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 룰 조회 저장소.
 *
 * <p><b>규약: 모든 조회는 기준일(baseDate)이 필수다. "현재 유효" 조회 API는 만들지 않는다.</b>
 * 어떤 기준일을 쓰는지는 룰의 성격이 결정한다 — 회차성 요율은 업무 발생일,
 * 한도/분급/환수 룰은 계약 체결일.
 *
 * <p>조회 결과가 없는 것(empty)은 "해당 룰 미적용"을 의미할 수 있으므로 예외가 아니라
 * Optional로 반환한다. 필수 룰의 부재는 호출 측(계산 Step)이 {@link RuleNotFoundException}으로
 * 승격시킨다. 동일 기준일에 유효 버전이 2개 이상이면 데이터 정합성 오류이므로
 * {@link AmbiguousRuleException}을 던진다.
 */
public interface RuleRepository {

    Optional<CommRateRule> findRate(Direction direction, InsurerCode insurerCd, ProductKey productKey,
                                    CommTypeCode commType, Integer installmentNo, LocalDate baseDate);

    Optional<CommTypeAttr> findCommTypeAttr(CommTypeCode commType, LocalDate baseDate);

    Optional<PayoutRateRule> findPayoutRate(String gradeCd, CommTypeCode commType, LocalDate baseDate);

    /** 1200%룰 버전. 기준일은 계약 체결일 — 2026-06-30 체결 계약은 empty(미적용). */
    Optional<LimitRule> findLimitRule(ChannelType channelType, LocalDate contractDate);

    /** 분급 커브. 기준일은 계약 체결일 — 2027-01-01 이전 체결은 empty(분급 미적용). */
    Optional<DeferralCurve> findDeferralCurve(LocalDate contractDate);

    /** 환수율 테이블. 기준일은 계약 체결일. 상품별 룰이 없으면 전 상품 공통 룰로 폴백. */
    Optional<ClawbackTable> findClawbackTable(ProductKey productKey, EventType eventType, LocalDate contractDate);

    Optional<OrgOverrideRate> findOrgOverrideRate(OrgLevel orgLevel, CommTypeCode commType, LocalDate baseDate);
}
