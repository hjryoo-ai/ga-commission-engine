package ga.comm.limit;

/** 한도 원장 불변식 위반 — 게이트를 거치지 않은 초과 전기 시도. */
public class LimitExceededException extends RuntimeException {
    public LimitExceededException(String message) {
        super(message);
    }
}
