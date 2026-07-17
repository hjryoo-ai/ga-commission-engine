package ga.comm.deferral;

import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.time.CloseYm;

/** 분급 지급 조건(POLICY_INFORCE) 판정 포트 — 계약이 해당월에 유지 중인가. */
@FunctionalInterface
public interface PolicyStatusProvider {

    boolean isInforce(PolicyNo policyNo, CloseYm asOf);
}
