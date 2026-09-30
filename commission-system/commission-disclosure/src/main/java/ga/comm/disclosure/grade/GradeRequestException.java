package ga.comm.disclosure.grade;

/** 요청 오류(400). code: INVALID_REQUEST | AS_OF_IN_FUTURE | UNKNOWN_PRODUCT_GROUP. */
public class GradeRequestException extends RuntimeException {

    private final String code;

    public GradeRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static GradeRequestException invalid(String message) {
        return new GradeRequestException("INVALID_REQUEST", message);
    }

    public String code() {
        return code;
    }
}
