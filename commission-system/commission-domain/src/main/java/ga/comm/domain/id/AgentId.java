package ga.comm.domain.id;

/** 설계사 위촉코드. */
public record AgentId(String value) {
    public AgentId {
        if (value == null || value.isBlank() || value.length() > 20) {
            throw new IllegalArgumentException("설계사 코드가 올바르지 않습니다: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
