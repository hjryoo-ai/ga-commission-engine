package ga.comm.app.support;

import ga.comm.deferral.PolicyStatusProvider;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.time.CloseYm;

/**
 * 분급 유지 판정 <b>placeholder</b> — 항상 "유지 중"으로 답한다. 실 운영 판정(계약 상태 원장 조회로
 * 실효/해약/부활 반영)은 운영 전환 항목이다(설계서 §12).
 *
 * <p><b>명명 클래스인 이유</b>: 이 placeholder가 실 지급 판정(분급 RELEASE)에 쓰이는 것을 <b>타입으로
 * 감지</b>해 운영(prod) 기동을 차단하기 위함이다({@link PolicyStatusProviderGuard}). "항상-유지"가
 * 도래분을 무조건 지급 투입하면 실효 계약에도 분급이 나가므로, 문서 표시만으로는 불충분하고 구조로
 * 배포를 막아야 한다(부록 B-13 — 침묵 기본값 대신 시끄러운 실패).
 */
public final class PlaceholderPolicyStatusProvider implements PolicyStatusProvider {

    @Override
    public boolean isInforce(PolicyNo policyNo, CloseYm asOf) {
        return true;
    }
}
