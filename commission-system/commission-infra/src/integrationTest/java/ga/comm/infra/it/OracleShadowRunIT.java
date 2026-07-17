package ga.comm.infra.it;

import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.RecipientType;
import ga.comm.infra.OraclePersistence;
import ga.comm.rule.fixture.RuleFixtures;
import ga.comm.shadow.ExternalSettlementRow;
import ga.comm.shadow.ShadowDiffType;
import ga.comm.shadow.ShadowRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 섀도 런 (실 Oracle, Phase 15) — 골든셋 러너와 달리 <b>실 스토어 경로</b>다. 신계약을 실제 Oracle
 * COMM_CALC에 계산·저장한 뒤, 외부 정산 결과와 대조해 차이가 유형별로 분류됨을 검증한다.
 * 자체 순액은 {@code NetAmountCalculator}(전 상태 합산)로 {@code OracleCommCalcStore}에서 산출된다.
 */
class OracleShadowRunIT {

    private final OraclePersistence persistence = OracleTestSupport.persistence();

    @BeforeEach
    void clean() {
        OracleTestSupport.cleanAll();
    }

    @Test
    void 외부_정산과의_차이가_유형별로_분류된다() {
        // 실 Oracle에 신계약 계산·저장 → AGENT FY_COMM 1,890,000 + 조직 오버라이드 T1/B1/H1(105k/63k/42k), 202608
        CommissionCalculator calculator =
                OracleBatchSupport.calculator(persistence, RuleFixtures.standardRules());
        persistence.inTx(() -> calculator.process(EventFixtures.newContract()));

        List<ExternalSettlementRow> external = List.of(
                row(RecipientType.AGENT, "A-1001", CommTypeCode.FY_COMM, 1_890_000),   // MATCH
                row(RecipientType.ORG, "T1", CommTypeCode.OVERRIDE, 100_000),         // 실 105,000 → RATE_DIFF
                row(RecipientType.AGENT, "A-1001", CommTypeCode.RENEWAL, 50_000));    // 내부 없음 → MISSING
        // B1/H1 오버라이드는 외부에 없음 → UNEXPECTED 2건

        ShadowRunService shadow = new ShadowRunService(persistence.commCalcStore());
        ShadowRunService.ShadowReport report =
                persistence.inTx(() -> shadow.compare(external, List.of(CloseYm.of("202608")), 0));

        assertThat(report.byType(ShadowDiffType.MATCH)).extracting(d -> d.commType().value())
                .containsExactly("FY_COMM");
        assertThat(report.byType(ShadowDiffType.RATE_DIFF)).singleElement()
                .satisfies(d -> {
                    assertThat(d.recipientId()).isEqualTo("T1");
                    assertThat(d.internal()).isEqualTo(Money.won(105_000));
                    assertThat(d.external()).isEqualTo(Money.won(100_000));
                });
        assertThat(report.byType(ShadowDiffType.MISSING)).extracting(d -> d.commType().value())
                .containsExactly("RENEWAL");
        assertThat(report.byType(ShadowDiffType.UNEXPECTED)).hasSize(2)
                .allSatisfy(d -> assertThat(d.commType()).isEqualTo(CommTypeCode.OVERRIDE));
        assertThat(report.clean()).isFalse();
    }

    private static ExternalSettlementRow row(RecipientType type, String id, CommTypeCode commType, long won) {
        return new ExternalSettlementRow(type, id, commType, CloseYm.of("202608"), Money.won(won));
    }
}
