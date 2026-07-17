package ga.comm.domain.id;

/** 증권번호. */
public record PolicyNo(String value) {
    public PolicyNo {
        if (value == null || value.isBlank() || value.length() > 30) {
            throw new IllegalArgumentException("증권번호가 올바르지 않습니다: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
