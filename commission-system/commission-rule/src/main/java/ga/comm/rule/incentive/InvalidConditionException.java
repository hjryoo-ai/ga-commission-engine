package ga.comm.rule.incentive;

/**
 * 시책 조건식이 부적합하거나(파싱 불가·boolean 아님) 샌드박스에서 거부된 경우.
 *
 * <p>등록·승인 시점: 승인 거부(fail-fast, 부록 B-13). 계산 시점: 계산 거부(침묵 스킵 금지).
 * 어느 경우든 조건식을 조용히 통과·스킵시키지 않는다.
 */
public class InvalidConditionException extends RuntimeException {

    public InvalidConditionException(String message) {
        super(message);
    }

    public InvalidConditionException(String message, Throwable cause) {
        super(message, cause);
    }
}
