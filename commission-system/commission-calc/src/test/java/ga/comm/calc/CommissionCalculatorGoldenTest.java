package ga.comm.calc;

import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.domain.type.CalcStatus;
import ga.comm.domain.type.RecipientType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계산 파이프라인의 <b>불변식·거동</b> 테스트 (설계서 §10).
 *
 * <p>신계약/회차입금/시책의 <b>골든 값</b> 케이스는 CSV 골든셋으로 이관됐다(Phase 14 —
 * {@code commission-settlement/src/test/resources/golden/cases/01,02,03,04}). 이 파일에는 값이
 * 아니라 거동을 고정하는 테스트만 남긴다: 멱등 수신, 근거(rule_versions·calc_trace) 박제, 불변 원장
 * 상태 전이. (골든셋과 별개로 존치하는 성질의 테스트다.)
 */
class CommissionCalculatorGoldenTest {

    private CalcTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new CalcTestHarness();
    }

    @Test
    @DisplayName("멱등: 같은 event_key 재수신은 재계산하지 않는다")
    void 중복_이벤트는_멱등_처리된다() {
        CommissionCalculator calculator = harness.calculator();
        List<CommCalcRecord> first = calculator.process(EventFixtures.newContract());
        List<CommCalcRecord> second = calculator.process(EventFixtures.newContract());

        assertThat(harness.calcStore.all()).hasSize(first.size());
        assertThat(second).extracting(CommCalcRecord::calcId)
                .containsExactlyInAnyOrderElementsOf(first.stream().map(CommCalcRecord::calcId).toList());
    }

    @Test
    @DisplayName("근거 박제: rule_versions와 calc_trace가 저장된다")
    void 룰버전과_trace가_박제된다() {
        List<CommCalcRecord> records = harness.calculator().process(EventFixtures.newContract());

        CommCalcRecord agentLine = records.stream()
                .filter(r -> r.recipientType() == RecipientType.AGENT).findFirst().orElseThrow();
        assertThat(agentLine.ruleVersions())
                .contains("COMM_RATE")
                .contains("AGENT_PAYOUT_RATE")
                .contains("COMM_TYPE_MST");
        assertThat(agentLine.calcTrace())
                .contains("BASE_COMM_V1")
                .contains("PAYOUT_RATE_V1");
    }

    @Test
    @DisplayName("불변 원장: 상태 전이 외 UPDATE 경로가 없고, 허용되지 않는 전이는 거부된다")
    void 불변_원장_상태전이_검증() {
        List<CommCalcRecord> records = harness.calculator().process(EventFixtures.newContract());
        long calcId = records.get(0).calcId();

        harness.calcStore.transition(calcId, CalcStatus.CONFIRMED);
        harness.calcStore.transition(calcId, CalcStatus.PAID);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        harness.calcStore.transition(calcId, CalcStatus.CALCULATED))
                .isInstanceOf(IllegalStateException.class);
    }
}
