package ga.comm.inbound.adapter;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.inbound.InboundStatement;
import ga.comm.inbound.StatementParseException;
import ga.comm.inbound.fixture.InMemoryInboundStatementStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SamlifeCsvAdapterTest {

    private final SamlifeCsvAdapter adapter = new SamlifeCsvAdapter();

    private static final String SAMPLE = """
            증권번호,상품코드,수수료유형,회차,금액
            POL-2026-0001,WHOLE-LIFE-20Y,01,,2100000
            POL-2026-0001,WHOLE-LIFE-20Y,01,2,45000
            POL-2026-0001,WHOLE-LIFE-20Y,03,,1000000
            """;

    @Test
    void 정상_명세를_정규화한다() {
        List<InboundStatement> rows = adapter.parse(CloseYm.of("202608"), SAMPLE);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).commType()).isEqualTo(CommTypeCode.FY_COMM);
        assertThat(rows.get(0).installmentNo()).isNull();
        assertThat(rows.get(0).amount()).isEqualTo(Money.won(2_100_000));
        assertThat(rows.get(1).installmentNo()).isEqualTo(2);
        assertThat(rows.get(2).commType()).isEqualTo(CommTypeCode.INCENTIVE);
    }

    @Test
    void 미등록_유형코드는_조용히_누락되지_않고_실패한다() {
        String bad = "h\nPOL-1,PRD-1,99,,1000";
        assertThatThrownBy(() -> adapter.parse(CloseYm.of("202608"), bad))
                .isInstanceOf(StatementParseException.class)
                .hasMessageContaining("99");
    }

    @Test
    void 컬럼수_불일치는_행번호와_함께_실패한다() {
        String bad = "h\nPOL-1,PRD-1,01,1000";
        assertThatThrownBy(() -> adapter.parse(CloseYm.of("202608"), bad))
                .isInstanceOf(StatementParseException.class)
                .hasMessageContaining("line 2");
    }

    @Test
    void 중복_수신은_멱등_저장된다() {
        InMemoryInboundStatementStore store = new InMemoryInboundStatementStore();
        List<InboundStatement> rows = adapter.parse(CloseYm.of("202608"), SAMPLE);

        assertThat(store.saveAll(rows)).isEqualTo(3);
        assertThat(store.saveAll(rows)).isZero();  // 재수신 → 신규 0건
        assertThat(store.findByYmAndInsurer(CloseYm.of("202608"), adapter.insurerCd())).hasSize(3);
    }
}
