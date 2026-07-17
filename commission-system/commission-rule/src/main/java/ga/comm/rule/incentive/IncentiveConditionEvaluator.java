package ga.comm.rule.incentive;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 시책 조건식 평가 엔진 (Phase 13) — 이 시스템에서 유일하게 "사용자 입력(관리자 등록 조건식)"이
 * 실행되는 표면이다. 따라서 전적으로 <b>샌드박스</b> 안에서만 평가한다.
 *
 * <p><b>샌드박스</b>: {@link SimpleEvaluationContext#forReadOnlyDataBinding()} — 읽기 전용 프로퍼티
 * 접근만 허용하고 타입 참조({@code T(...)}), 빈 참조({@code @bean}), 생성자({@code new ...}),
 * 메서드 호출, 대입을 전부 거부한다. 조건식은 {@link IncentiveConditionInput}의 getter 프로퍼티와
 * SpEL 연산자(비교/논리/산술/matches)만 쓸 수 있다.
 *
 * <p><b>결정론</b>: 입력은 {@link IncentiveConditionInput} 스냅샷뿐 — 현재시각·난수·외부 조회가
 * 닿을 수 없다. 같은 입력·같은 조건식은 항상 같은 결과다(§6.5 replay 전제).
 *
 * <p><b>fail-fast</b>: {@link #validate}는 등록·승인 시점에 파싱 + 시평가로 부적합 조건식을 걷어낸다
 * (불가 시 승인 거부). {@link #matches}는 평가 오류를 삼키지 않고 던진다(계산 거부). SpEL 타입은
 * 이 클래스 밖으로 노출되지 않는다.
 */
public final class IncentiveConditionEvaluator {

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> compiled = new ConcurrentHashMap<>();

    /** 등록·승인 시점 검증 — 파싱 실패·샌드박스 위반·비-boolean이면 던진다(승인 거부). */
    public void validate(String conditionExpr) {
        // 대표 입력으로 시평가: T()/new/@/메서드 호출이 있으면 샌드박스가 여기서 거부한다.
        Object result = evaluate(conditionExpr, IncentiveConditionInput.sample());
        if (!(result instanceof Boolean)) {
            throw new InvalidConditionException(
                    "조건식은 boolean을 반환해야 합니다: " + conditionExpr + " → " + result);
        }
    }

    /** 계산 시점 판정 — 평가 오류는 던진다(계산 거부, 침묵 스킵 금지). */
    public boolean matches(String conditionExpr, IncentiveConditionInput input) {
        Object result = evaluate(conditionExpr, input);
        if (!(result instanceof Boolean matched)) {
            throw new InvalidConditionException(
                    "조건식 평가 결과가 boolean이 아닙니다: " + conditionExpr + " → " + result);
        }
        return matched;
    }

    private Object evaluate(String conditionExpr, IncentiveConditionInput input) {
        Expression expression = compile(conditionExpr);
        // 매 평가마다 새 샌드박스 컨텍스트 — 상태 누출 없음.
        EvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        try {
            return expression.getValue(context, input);
        } catch (EvaluationException e) {
            throw new InvalidConditionException(
                    "조건식이 샌드박스에서 거부되었습니다(허용되지 않은 구문/평가 오류): "
                            + conditionExpr + " — " + e.getMessage(), e);
        }
    }

    private Expression compile(String conditionExpr) {
        return compiled.computeIfAbsent(conditionExpr, expr -> {
            try {
                return parser.parseExpression(expr);
            } catch (ParseException e) {
                throw new InvalidConditionException("조건식 파싱 실패: " + expr + " — " + e.getMessage(), e);
            }
        });
    }
}
