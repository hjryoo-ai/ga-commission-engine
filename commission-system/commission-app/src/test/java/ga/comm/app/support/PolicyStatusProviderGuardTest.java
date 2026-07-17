package ga.comm.app.support;

import ga.comm.deferral.PolicyStatusProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * placeholder 운영 차단 가드 검증 (Phase 16 마무리) — 문서 표시가 아니라 <b>구조</b>로 배포를 막는다.
 */
class PolicyStatusProviderGuardTest {

    @Test
    void 운영에서_placeholder면_기동을_차단한다() {
        assertThatThrownBy(() -> PolicyStatusProviderGuard.verifyProductionSafe(
                new PlaceholderPolicyStatusProvider(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("운영 배포 금지");
    }

    @Test
    void 비운영에서는_placeholder도_통과한다() {
        // dev/test는 placeholder로 조립·기동 가능(스모크·골든셋 등)
        assertThatCode(() -> PolicyStatusProviderGuard.verifyProductionSafe(
                new PlaceholderPolicyStatusProvider(), false))
                .doesNotThrowAnyException();
    }

    @Test
    void 운영이라도_실_구현이면_통과한다() {
        // placeholder 타입이 아닌 실 판정 구현을 배선하면 운영 기동 허용
        PolicyStatusProvider real = (policyNo, asOf) -> true;
        assertThatCode(() -> PolicyStatusProviderGuard.verifyProductionSafe(real, true))
                .doesNotThrowAnyException();
    }
}
