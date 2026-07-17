package ga.comm.rule.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * fail-fast 규약 (설계서 §6.1.4, 부록 B-13): over_limit_action에 코드 기본값을 두지 않는다.
 * 정책이 누락된 룰 데이터로는 한도 대상 계산이 시작조차 되지 않아야 한다 — 침묵 기본값으로
 * 잘못된 정책의 지급이 나가는 것을 원천 차단한다.
 */
class LimitRuleFailFastTest {

    @Test
    void over_limit_action이_누락된_룰은_적재_시점에_명시적으로_거부된다() {
        assertThatThrownBy(() -> new LimitRule(9001L, ChannelType.GA_TO_AGENT,
                new BigDecimal("12.00"), 12, null, true,
                EffectivePeriod.from(LocalDate.of(2026, 7, 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("over_limit_action 누락")
                .hasMessageContaining("fail-fast");
    }
}
