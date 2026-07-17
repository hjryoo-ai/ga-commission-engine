package ga.comm.domain.id;

/** 원수사(보험사) 코드. */
public record InsurerCode(String value) {
    public InsurerCode {
        if (value == null || value.isBlank() || value.length() > 10) {
            throw new IllegalArgumentException("보험사 코드가 올바르지 않습니다: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
