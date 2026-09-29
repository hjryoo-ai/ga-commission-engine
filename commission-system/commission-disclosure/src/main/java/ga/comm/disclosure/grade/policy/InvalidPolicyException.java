package ga.comm.disclosure.grade.policy;

import java.util.List;

/** 정책 데이터가 규약을 어김(겹침·빈틈·ordinal 중복·라벨 누락·형식). 로드 시점 fail-fast — 스냅샷을 만들지 않는다. */
public class InvalidPolicyException extends RuntimeException {

    private final List<String> problems;

    public InvalidPolicyException(List<String> problems) {
        super("invalid disclosure policy: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
