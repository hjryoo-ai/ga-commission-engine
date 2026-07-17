package ga.comm.infra.it;

import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.StepConfig;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.calc.step.IncentiveV2Step;
import ga.comm.infra.OraclePersistence;
import ga.comm.rule.AmbiguousRuleException;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.rule.incentive.IncentiveConditionEvaluator;
import ga.comm.rule.model.EffectivePeriod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시책 해석 fail-fast (실 Oracle, Phase 15 선행). 같은 incentive_cd에 유효기간이 겹치는 ACTIVE가
 * 2건이면 — {@code ux_incentive_active}(V102)가 못 막는 "개시일이 다른" 겹침 경합 — 계산이
 * {@link AmbiguousRuleException}으로 거부됨을 <b>실제 Oracle 리포지토리 경로</b>로 박제한다.
 *
 * <p>이 마개(부록 B-13 확장)가 있으면 겹침이 어떻게 생기든(동시 승인 등) 시책이 이중 편입되어 지급되는
 * 일이 없다 — 최악은 "계산이 시끄럽게 실패"다. 이연된 승인 러너는 이 정합성 보장 위의 편의 개선이다.
 */
class OracleIncentiveResolutionIT {

    private final OraclePersistence persistence = OracleTestSupport.persistence();

    @BeforeEach
    void clean() {
        OracleTestSupport.cleanAll();
    }

    @Test
    void 같은_코드_겹치는_ACTIVE_시책은_계산을_거부한다() {
        // 개시일 01-01 / 06-01 (인덱스 통과) + apply_to 기본값 → 2026-08-01 이벤트에 둘 다 유효·겹침.
        seedTwoActiveSameCd();

        CommissionCalculator calculator = OracleBatchSupport.calculator(persistence,
                RuleFixtures.standardRules(),
                List.of(new StepConfig(new IncentiveV2Step(persistence.incentiveRepository(),
                        new IncentiveConditionEvaluator()),
                        EffectivePeriod.from(LocalDate.of(2026, 1, 1)), 31)));

        assertThatThrownBy(() -> persistence.inTx(() -> calculator.process(EventFixtures.newContract())))
                .isInstanceOf(AmbiguousRuleException.class)
                .hasMessageContaining("DUP");
    }

    private void seedTwoActiveSameCd() {
        for (long[] seed : List.of(new long[]{960001, 20260101}, new long[]{960002, 20260601})) {
            JdbcRuleSeeder.execute(OracleTestSupport.dataSource(), """
                    INSERT INTO INCENTIVE_MST (incentive_id, incentive_cd, insurer_cd, product_key,
                        channel, condition_expr, payout_kind, fixed_amount, apply_from, version_no, status)
                    VALUES (?, 'DUP', 'SAMLIFE', 'WHOLE-LIFE-20Y', NULL, 'premium >= 0', 'FIXED',
                        500000, ?, 1, 'ACTIVE')
                    """, ps -> {
                ps.setLong(1, seed[0]);
                String ymd = String.valueOf(seed[1]);
                ps.setDate(2, Date.valueOf(LocalDate.of(Integer.parseInt(ymd.substring(0, 4)),
                        Integer.parseInt(ymd.substring(4, 6)), Integer.parseInt(ymd.substring(6)))));
            });
        }
    }
}
