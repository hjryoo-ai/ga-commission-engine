package ga.comm.domain.id;

/** 상품·플랜·납기 조합 키. */
public record ProductKey(String value) {
    public ProductKey {
        if (value == null || value.isBlank() || value.length() > 40) {
            throw new IllegalArgumentException("상품 키가 올바르지 않습니다: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
