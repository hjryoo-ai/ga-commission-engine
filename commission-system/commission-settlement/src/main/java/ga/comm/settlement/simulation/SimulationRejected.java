package ga.comm.settlement.simulation;

import java.util.Objects;

/**
 * 시뮬레이션 요청 거부 — 포털 계약 v1.3의 Problem {@code code}와 HTTP 상태(컨트롤러가 그대로 옮긴다).
 */
public final class SimulationRejected extends RuntimeException {

    public enum Kind {
        /** 400 */
        INVALID_REQUEST(400),
        /** 404 — 엔진에 없는 설계사 */
        AGENT_NOT_FOUND(404),
        /** 404 — 그 설계사의 계약이 아니다 */
        POLICY_NOT_FOUND(404),
        /** 422 — insurerCd가 없고 유효 요율의 보험사가 하나가 아니다 */
        INSURER_AMBIGUOUS(422),
        /** 422 — 엔진이 시뮬레이션하지 않는 eventType */
        UNSUPPORTED_EVENT_TYPE(422);

        private final int status;

        Kind(int status) {
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final Kind kind;

    public SimulationRejected(Kind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind);
    }

    public Kind kind() {
        return kind;
    }
}
