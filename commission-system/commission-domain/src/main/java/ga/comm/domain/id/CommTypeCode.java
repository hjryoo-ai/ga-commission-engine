package ga.comm.domain.id;

/**
 * 수수료 유형 코드 (예: FY_COMM, RENEWAL, INCENTIVE, SETTLEMENT_SUPPORT, OVERRIDE,
 * DEFERRED, MAINTENANCE, CLAWBACK).
 *
 * <p>enum이 아닌 이유: 유형 자체가 마스터 데이터(COMM_TYPE_MST)로 관리되며,
 * 한도 포함 여부/반올림 정책 같은 속성이 유효기간을 갖고 바뀌기 때문이다.
 */
public record CommTypeCode(String value) {
    public CommTypeCode {
        if (value == null || value.isBlank() || value.length() > 20) {
            throw new IllegalArgumentException("수수료 유형 코드가 올바르지 않습니다: " + value);
        }
    }

    public static final CommTypeCode FY_COMM = new CommTypeCode("FY_COMM");
    public static final CommTypeCode RENEWAL = new CommTypeCode("RENEWAL");
    public static final CommTypeCode INCENTIVE = new CommTypeCode("INCENTIVE");
    public static final CommTypeCode SETTLEMENT_SUPPORT = new CommTypeCode("SETTLEMENT_SUPPORT");
    public static final CommTypeCode OVERRIDE = new CommTypeCode("OVERRIDE");
    public static final CommTypeCode DEFERRED = new CommTypeCode("DEFERRED");
    public static final CommTypeCode MAINTENANCE = new CommTypeCode("MAINTENANCE");
    public static final CommTypeCode CLAWBACK = new CommTypeCode("CLAWBACK");

    @Override
    public String toString() {
        return value;
    }
}
