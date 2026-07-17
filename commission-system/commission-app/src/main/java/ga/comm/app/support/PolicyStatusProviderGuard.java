package ga.comm.app.support;

import ga.comm.deferral.PolicyStatusProvider;

/**
 * 운영 배포 안전 가드 (Phase 16 마무리) — 운영(prod)에서 분급 유지 판정이 placeholder면 <b>기동을
 * 실패</b>시킨다. placeholder(항상-유지)가 실 지급 판정에 쓰이는 경로를 <b>구조로 차단</b>한다
 * (문서 표시만으로는 불충분, 부록 B-13). 컨텍스트 초기화 마무리 단계에서 검사하므로, 위반 시 앱은
 * "떠서 잘못 지급"하지 않고 아예 뜨지 않는다.
 *
 * <p>실 판정 구현을 배선하면(placeholder가 아닌 {@link PolicyStatusProvider}) 통과한다.
 */
public final class PolicyStatusProviderGuard {

    private PolicyStatusProviderGuard() {
    }

    /** prod에서 provider가 placeholder면 IllegalStateException으로 기동 차단. */
    public static void verifyProductionSafe(PolicyStatusProvider provider, boolean prodActive) {
        if (prodActive && provider instanceof PlaceholderPolicyStatusProvider) {
            throw new IllegalStateException(
                    "운영 배포 금지: PolicyStatusProvider가 placeholder(항상-유지)입니다. "
                            + "분급 유지 판정이 실효/해약 계약에도 도래분을 지급 투입하게 되므로, "
                            + "실 계약상태 판정 구현을 배선한 뒤 운영에 배포하세요(설계서 §12 운영 전환 체크리스트).");
        }
    }
}
