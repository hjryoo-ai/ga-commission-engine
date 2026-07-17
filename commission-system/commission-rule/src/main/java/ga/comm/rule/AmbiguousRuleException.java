package ga.comm.rule;

/** 동일 기준일에 유효한 룰 버전이 2개 이상 — 데이터 정합성 오류. */
public class AmbiguousRuleException extends RuntimeException {
    public AmbiguousRuleException(String message) {
        super(message);
    }
}
