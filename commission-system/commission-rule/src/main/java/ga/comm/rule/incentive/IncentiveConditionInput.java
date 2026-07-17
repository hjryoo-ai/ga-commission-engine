package ga.comm.rule.incentive;

/**
 * 시책 조건식의 입력 스냅샷 (Phase 13) — 조건식이 참조할 수 있는 <b>전부</b>다.
 *
 * <p>결정론(순수 함수) 보장: 계약/이벤트/설계사 속성 + 사전 집계된 실적 스냅샷 값만 노출한다.
 * 현재시각·난수·외부 조회는 노출하지 않는다. 모든 필드는 JavaBean getter로 제공되어
 * {@link org.springframework.expression.spel.support.SimpleEvaluationContext}의 데이터 바인딩
 * 프로퍼티 접근으로만 읽힌다(메서드 호출 아님). 실적 필드는 미집계 시 0으로, null을 노출하지 않아
 * 조건 평가가 결정적이다.
 *
 * <p>실적 지표는 1차 고정 집합(fycSum/contractCount/persistencyBp)이며, 집계 파이프라인 자체는
 * 이번 범위 밖이다. 새 지표는 후속 Phase에서 getter로 추가한다.
 */
public final class IncentiveConditionInput {

    private final long premium;
    private final String insurerCd;
    private final String productKey;
    private final String channel;
    private final String eventType;
    private final String gradeCd;
    private final String agentId;
    private final long fycSum;
    private final int contractCount;
    private final int persistencyBp;

    private IncentiveConditionInput(Builder b) {
        this.premium = b.premium;
        this.insurerCd = b.insurerCd == null ? "" : b.insurerCd;
        this.productKey = b.productKey == null ? "" : b.productKey;
        this.channel = b.channel == null ? "" : b.channel;
        this.eventType = b.eventType == null ? "" : b.eventType;
        this.gradeCd = b.gradeCd == null ? "" : b.gradeCd;
        this.agentId = b.agentId == null ? "" : b.agentId;
        this.fycSum = b.fycSum;
        this.contractCount = b.contractCount;
        this.persistencyBp = b.persistencyBp;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 등록·승인 시점 시평가(trial evaluation)용 대표 입력 — 모든 필드가 채워져 null·NPE가 없다. */
    public static IncentiveConditionInput sample() {
        return builder()
                .premium(300_000).insurerCd("SAMPLE").productKey("SAMPLE").channel("GA")
                .eventType("NEW").gradeCd("SR").agentId("A-0000")
                .fycSum(0).contractCount(0).persistencyBp(0)
                .build();
    }

    // ---- JavaBean getters: 조건식이 참조하는 프로퍼티 이름 ----
    public long getPremium() {
        return premium;
    }

    public String getInsurerCd() {
        return insurerCd;
    }

    public String getProductKey() {
        return productKey;
    }

    public String getChannel() {
        return channel;
    }

    public String getEventType() {
        return eventType;
    }

    public String getGradeCd() {
        return gradeCd;
    }

    public String getAgentId() {
        return agentId;
    }

    public long getFycSum() {
        return fycSum;
    }

    public int getContractCount() {
        return contractCount;
    }

    public int getPersistencyBp() {
        return persistencyBp;
    }

    public static final class Builder {
        private long premium;
        private String insurerCd;
        private String productKey;
        private String channel;
        private String eventType;
        private String gradeCd;
        private String agentId;
        private long fycSum;
        private int contractCount;
        private int persistencyBp;

        public Builder premium(long v) {
            this.premium = v;
            return this;
        }

        public Builder insurerCd(String v) {
            this.insurerCd = v;
            return this;
        }

        public Builder productKey(String v) {
            this.productKey = v;
            return this;
        }

        public Builder channel(String v) {
            this.channel = v;
            return this;
        }

        public Builder eventType(String v) {
            this.eventType = v;
            return this;
        }

        public Builder gradeCd(String v) {
            this.gradeCd = v;
            return this;
        }

        public Builder agentId(String v) {
            this.agentId = v;
            return this;
        }

        public Builder fycSum(long v) {
            this.fycSum = v;
            return this;
        }

        public Builder contractCount(int v) {
            this.contractCount = v;
            return this;
        }

        public Builder persistencyBp(int v) {
            this.persistencyBp = v;
            return this;
        }

        public IncentiveConditionInput build() {
            return new IncentiveConditionInput(this);
        }
    }
}
