package ga.comm.rule.incentive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시책 조건식 샌드박스 (설계서 §3.6, Phase 13) — 이 시스템 유일의 "사용자 입력 실행" 표면.
 *
 * <p>관리자가 등록하는 조건식이 곧 코드 실행 통로가 되지 않음을 <b>금지 구문 거부 테스트로 고정</b>한다.
 * SimpleEvaluationContext(데이터 바인딩 전용)는 타입 참조·빈·생성자·메서드 호출·대입을 전부 거부한다.
 */
class IncentiveConditionEvaluatorTest {

    private final IncentiveConditionEvaluator evaluator = new IncentiveConditionEvaluator();

    @ParameterizedTest(name = "샌드박스 거부: {0}")
    @ValueSource(strings = {
            "T(java.lang.Runtime).getRuntime().exec('calc')",   // 타입 참조 → RCE 통로
            "T(java.lang.System).exit(0)",                      // 타입 참조
            "new java.io.File('/etc/passwd').exists()",         // 생성자
            "new java.lang.ProcessBuilder('sh').start()",       // 생성자 → RCE
            "@systemProperties",                                // 빈 참조
            "''.getClass().getName() == 'x'",                   // 메서드 호출(리플렉션 통로)
            "getInsurerCd().toLowerCase() == 'x'",              // 프로퍼티 결과에 메서드 호출
            "insurerCd.toLowerCase() == 'x'",                   // 메서드 호출
            "T(java.lang.Math).random() > 0.5",                 // 타입 참조(+비결정)
    })
    void 위험_구문은_전부_거부된다(String malicious) {
        assertThatThrownBy(() -> evaluator.validate(malicious))
                .isInstanceOf(InvalidConditionException.class);
    }

    @Test
    void 대입은_거부된다() {
        // 읽기 전용 컨텍스트 — 프로퍼티 쓰기 시도는 거부
        assertThatThrownBy(() -> evaluator.validate("premium = 500000"))
                .isInstanceOf(InvalidConditionException.class);
    }

    @Test
    void 파싱_불가는_승인_거부() {
        assertThatThrownBy(() -> evaluator.validate("premium >= "))
                .isInstanceOf(InvalidConditionException.class);
    }

    @Test
    void boolean이_아니면_승인_거부() {
        assertThatThrownBy(() -> evaluator.validate("premium + 1"))
                .isInstanceOf(InvalidConditionException.class)
                .hasMessageContaining("boolean");
    }

    @ParameterizedTest(name = "허용: {0}")
    @ValueSource(strings = {
            "premium >= 300000",
            "insurerCd == 'SAMLIFE' and contractCount >= 5",
            "productKey matches 'WHOLE.*'",
            "premium >= 300000 or persistencyBp > 8000",
            "eventType == 'NEW' and gradeCd == 'SR'",
            "fycSum > 1000000 and !(channel == 'ONLINE')",
    })
    void 데이터_바인딩_비교_논리_matches는_허용된다(String valid) {
        // 검증(등록 시점) 통과 — 예외 없음
        evaluator.validate(valid);
    }

    @Test
    void 조건식은_입력에_대해_결정적으로_판정된다() {
        IncentiveConditionInput match = IncentiveConditionInput.builder()
                .premium(300_000).insurerCd("SAMLIFE").contractCount(5).build();
        IncentiveConditionInput noMatch = IncentiveConditionInput.builder()
                .premium(200_000).insurerCd("SAMLIFE").contractCount(5).build();

        String expr = "premium >= 300000 and insurerCd == 'SAMLIFE' and contractCount >= 5";
        assertThat(evaluator.matches(expr, match)).isTrue();
        assertThat(evaluator.matches(expr, noMatch)).isFalse();
        // 같은 입력·같은 식은 항상 같은 결과 (결정론)
        assertThat(evaluator.matches(expr, match)).isEqualTo(evaluator.matches(expr, match));
    }

    @Test
    void 미집계_실적은_0으로_결정적_판정() {
        // contractCount를 지정하지 않은 입력 → 0, 조건 5 이상은 false (침묵 스킵이 아니라 정상 판정)
        IncentiveConditionInput input = IncentiveConditionInput.builder().premium(300_000).build();
        assertThat(evaluator.matches("contractCount >= 5", input)).isFalse();
    }
}
