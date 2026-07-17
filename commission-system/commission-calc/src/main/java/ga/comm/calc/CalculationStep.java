package ga.comm.calc;

/**
 * 계산 Step (설계서 §4.3). 새 규제 대응은 기존 Step 수정이 아니라
 * 새 Step 구현 + 유효기간 등록으로 한다 — stepId에 버전을 포함시킨다 (예: "LIMIT_GATE_V1").
 */
public interface CalculationStep {

    String stepId();

    /** 이벤트 유형·수급자 유형 등 컨텍스트 필터. 유효기간 필터는 StepConfig가 담당한다. */
    boolean supports(CalcContext ctx);

    /** 라인 추가/치환 + trace 기록. */
    void apply(CalcContext ctx);
}
