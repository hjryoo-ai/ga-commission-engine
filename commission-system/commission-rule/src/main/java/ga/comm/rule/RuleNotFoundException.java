package ga.comm.rule;

/** 계산에 필수인 룰이 기준일에 존재하지 않을 때. */
public class RuleNotFoundException extends RuntimeException {
    public RuleNotFoundException(String message) {
        super(message);
    }
}
