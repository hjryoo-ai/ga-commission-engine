package ga.comm.domain.id;

/** 조직(본부/지점/팀) 코드. */
public record OrgId(String value) {
    public OrgId {
        if (value == null || value.isBlank() || value.length() > 20) {
            throw new IllegalArgumentException("조직 코드가 올바르지 않습니다: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
