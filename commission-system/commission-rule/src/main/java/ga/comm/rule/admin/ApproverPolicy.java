package ga.comm.rule.admin;

/**
 * 승인자 실명 규약 (설계서 §6.6) — 운영 승인 경로의 서비스 계층 강제. 요율·시책 승인 서비스가 공유한다.
 *
 * <p>컨트롤러의 principal 검증에 더한 <b>심층 방어</b>다: 기술 계정 principal이 "system"으로 들어오거나
 * 컨트롤러를 거치지 않는 호출(러너·배치·직접 서비스 호출)이 있어도, 운영 승인 기록이 시드/테스트
 * 대역("system")과 뒤섞이지 않는다. 시드/테스트 경로는 인자 없는 approve 오버로드로 "system"을 쓴다.
 */
final class ApproverPolicy {

    private ApproverPolicy() {
    }

    static void requireReal(String approvedBy) {
        if (approvedBy == null || approvedBy.isBlank()) {
            throw new IllegalArgumentException("승인자는 필수입니다(§6.6 실명 요건)");
        }
        if (approvedBy.trim().equalsIgnoreCase("system")) {
            throw new IllegalArgumentException(
                    "운영 승인은 실명이어야 합니다 — \"system\"은 시드/테스트 대역 전용입니다(§6.6)");
        }
    }
}
