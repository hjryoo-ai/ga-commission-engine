package ga.comm.recon;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.inbound.InboundStatement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static ga.comm.recon.ReconService.DiffType.AMOUNT_MISMATCH;
import static ga.comm.recon.ReconService.DiffType.MATCH;
import static ga.comm.recon.ReconService.DiffType.MISSING_STATEMENT;
import static ga.comm.recon.ReconService.DiffType.UNEXPECTED_STATEMENT;
import static org.assertj.core.api.Assertions.assertThat;

/** 대사 차이 유형화 (설계서 §7) — 요율 차이/누락 검출. */
class ReconServiceTest {

    private final ReconService service = new ReconService();

    private InboundStatement statement(String policyNo, CommTypeCode type, Integer inst, long amount) {
        return new InboundStatement(new InsurerCode("SAMLIFE"), CloseYm.of("202608"),
                new PolicyNo(policyNo), new ProductKey("WHOLE-LIFE-20Y"), type, inst,
                Money.won(amount), Map.of());
    }

    @Test
    void 대사_차이를_유형화한다() {
        List<InboundStatement> statements = List.of(
                statement("POL-1", CommTypeCode.FY_COMM, null, 2_100_000),   // MATCH
                statement("POL-2", CommTypeCode.FY_COMM, 2, 40_000),         // 요율 차이 (기대 45,000)
                statement("POL-9", CommTypeCode.INCENTIVE, null, 500_000)    // 자체 계산에 없음
        );
        List<ExpectedRow> expected = List.of(
                new ExpectedRow(new PolicyNo("POL-1"), CommTypeCode.FY_COMM, null, Money.won(2_100_000)),
                new ExpectedRow(new PolicyNo("POL-2"), CommTypeCode.FY_COMM, 2, Money.won(45_000)),
                new ExpectedRow(new PolicyNo("POL-3"), CommTypeCode.FY_COMM, null, Money.won(700_000))  // 보험사 누락
        );

        ReconService.ReconReport report = service.reconcile(statements, expected);

        assertThat(report.byType(MATCH)).hasSize(1);
        assertThat(report.byType(AMOUNT_MISMATCH)).hasSize(1);
        assertThat(report.byType(AMOUNT_MISMATCH).get(0).diff()).isEqualTo(Money.won(5_000));
        assertThat(report.byType(MISSING_STATEMENT)).hasSize(1);
        assertThat(report.byType(MISSING_STATEMENT).get(0).diff()).isEqualTo(Money.won(700_000));
        assertThat(report.byType(UNEXPECTED_STATEMENT)).hasSize(1);

        // GA가 덜 받은 총액 = 5,000(요율차) + 700,000(누락)
        assertThat(report.totalShortfall()).isEqualTo(Money.won(705_000));
        assertThat(report.clean()).isFalse();
    }

    @Test
    void 같은_키의_분할_명세는_합산_후_비교한다() {
        List<InboundStatement> statements = List.of(
                statement("POL-1", CommTypeCode.FY_COMM, null, 1_000_000),
                statement("POL-1", CommTypeCode.FY_COMM, null, 1_100_000)  // 분할 지급
        );
        List<ExpectedRow> expected = List.of(
                new ExpectedRow(new PolicyNo("POL-1"), CommTypeCode.FY_COMM, null, Money.won(2_100_000)));

        ReconService.ReconReport report = service.reconcile(statements, expected);

        assertThat(report.clean()).isTrue();
    }
}
