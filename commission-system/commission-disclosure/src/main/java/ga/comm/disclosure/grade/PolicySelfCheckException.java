package ga.comm.disclosure.grade;

import java.util.List;

/** 응답 자기 검증 실패(422, POLICY_SELF_CHECK_FAILED) — 정책 데이터 모순 신호. 스냅샷을 만들지 않는다. */
public class PolicySelfCheckException extends RuntimeException {

    private final List<String> violations;

    public PolicySelfCheckException(List<String> violations) {
        super("grade/rank self-check failed: " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
