package ga.comm.domain.recipient;

import ga.comm.domain.type.RecipientType;

/** 수수료 수급자 (설계사 본인 또는 조직 오버라이드 수급자). */
public record Recipient(RecipientType type, String id) {
    public Recipient {
        if (type == null) {
            throw new IllegalArgumentException("수급자 유형은 null일 수 없습니다");
        }
        if (id == null || id.isBlank() || id.length() > 20) {
            throw new IllegalArgumentException("수급자 ID가 올바르지 않습니다: " + id);
        }
    }

    public boolean isAgent() {
        return type == RecipientType.AGENT;
    }
}
