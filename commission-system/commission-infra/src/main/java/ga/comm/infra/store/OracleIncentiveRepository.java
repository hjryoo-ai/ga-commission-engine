package ga.comm.infra.store;

import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;
import ga.comm.domain.money.Rate;
import ga.comm.infra.mapper.IncentiveAdminMapper;
import ga.comm.rule.IncentiveRepository;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.IncentiveRule;
import ga.comm.rule.model.PayoutKind;
import ga.comm.rule.model.RateStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** 시책 조회 Oracle 어댑터 (Phase 13). 기준일 유효 ACTIVE 시책 전체를 돌려준다. */
public class OracleIncentiveRepository implements IncentiveRepository {

    private final IncentiveAdminMapper mapper;

    public OracleIncentiveRepository(IncentiveAdminMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public List<IncentiveRule> findActiveAt(LocalDate baseDate) {
        return mapper.findActiveAt(baseDate).stream().map(OracleIncentiveRepository::toRule).toList();
    }

    /** 행 → 도메인. 어드민 어댑터도 재사용한다(요율의 OracleRuleRepository.toRate와 같은 패턴). */
    static IncentiveRule toRule(IncentiveAdminMapper.IncentiveRow row) {
        return new IncentiveRule(
                row.incentiveId,
                row.incentiveCd,
                row.insurerCd == null ? null : new InsurerCode(row.insurerCd),
                row.productKey == null ? null : new ProductKey(row.productKey),
                row.channel,
                row.conditionExpr,
                PayoutKind.valueOf(row.payoutKind),
                row.fixedAmount == null ? null : Money.won(row.fixedAmount),
                row.premiumRate == null ? null : Rate.of(row.premiumRate),
                EffectivePeriod.of(row.applyFrom, row.applyTo),
                row.versionNo,
                RateStatus.valueOf(row.status));
    }
}
