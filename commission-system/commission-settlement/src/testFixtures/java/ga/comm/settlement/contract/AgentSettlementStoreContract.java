package ga.comm.settlement.contract;

import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.RecipientType;
import ga.comm.settlement.AgentSettlementStore;
import ga.comm.settlement.SettlementRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** AgentSettlementStore 계약 테스트 (설계서 §8.7) — 마감 정산 내역 왕복 (지급 배치 입력). */
public abstract class AgentSettlementStoreContract {

    protected abstract AgentSettlementStore store();

    /** AGENT_SETTLEMENT_CALC.calc_id FK를 만족하는 계산 ID. */
    protected abstract long aCalcId();

    protected <T> T inTx(Supplier<T> work) {
        return work.get();
    }

    @Test
    void 정산_내역이_calcId_목록_순서까지_왕복_보존된다() {
        long calc1 = aCalcId();
        long calc2 = aCalcId();
        SettlementRow row = new SettlementRow(CloseYm.of("202608"), 1, RecipientType.AGENT, "A-1001",
                Money.won(1_890_000), Money.won(120_000), Money.ZERO, Money.won(1_770_000),
                List.of(calc1, calc2));

        inTx(() -> {
            store().saveAll(List.of(row));
            return null;
        });

        List<SettlementRow> reloaded = inTx(() -> store().findByCloseYm(CloseYm.of("202608")));
        assertThat(reloaded).containsExactly(row);
    }

    @Test
    void 음수_순액과_채권_이월이_왕복_보존된다() {
        SettlementRow row = new SettlementRow(CloseYm.of("202609"), 1, RecipientType.AGENT, "A-1001",
                Money.won(-300_000), Money.ZERO, Money.won(300_000), Money.ZERO, List.of());

        inTx(() -> {
            store().saveAll(List.of(row));
            return null;
        });

        assertThat(inTx(() -> store().findByCloseYm(CloseYm.of("202609")))).containsExactly(row);
    }

    @Test
    void 다른_마감월은_조회되지_않는다() {
        SettlementRow row = new SettlementRow(CloseYm.of("202610"), 1, RecipientType.ORG, "TEAM-01",
                Money.won(50_000), Money.ZERO, Money.ZERO, Money.won(50_000), List.of(aCalcId()));

        inTx(() -> {
            store().saveAll(List.of(row));
            return null;
        });

        assertThat(inTx(() -> store().findByCloseYm(CloseYm.of("202611")))).isEmpty();
    }

    @Test
    void 같은_마감월의_지급_런_회차가_구분되어_왕복된다() {
        SettlementRow run1 = new SettlementRow(CloseYm.of("202612"), 1, RecipientType.AGENT, "A-1001",
                Money.won(1_000_000), Money.ZERO, Money.ZERO, Money.won(1_000_000), List.of(aCalcId()));
        SettlementRow run2 = new SettlementRow(CloseYm.of("202612"), 2, RecipientType.AGENT, "A-2002",
                Money.won(700_000), Money.won(200_000), Money.ZERO, Money.won(500_000), List.of(aCalcId()));

        inTx(() -> {
            store().saveAll(List.of(run1));
            store().saveAll(List.of(run2));
            return null;
        });

        List<SettlementRow> reloaded = inTx(() -> store().findByCloseYm(CloseYm.of("202612")));
        assertThat(reloaded).containsExactly(run1, run2);
        assertThat(reloaded.stream().map(SettlementRow::runSeq)).containsExactly(1, 2);
    }
}
