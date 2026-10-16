package ga.comm.infra.it;

import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.RecipientType;
import ga.comm.infra.OraclePersistence;
import ga.comm.settlement.AgentSettlementStore;
import ga.comm.settlement.PayoutService;
import ga.comm.settlement.PayoutStatementStore;
import ga.comm.settlement.SettlementRow;
import ga.comm.settlement.contract.PayoutStatementStoreContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 지급명세 Oracle 어댑터(V107, E1 P6) — 계약 스위트 + 정산 행 하나에 명세 하나(UNIQUE)·정산 행 없이 명세 없음(NOT NULL). */
class OraclePayoutStatementStoreIT extends PayoutStatementStoreContract {

    private final OraclePersistence persistence = OracleTestSupport.persistence();

    @BeforeEach
    void clean() {
        OracleTestSupport.cleanAll();
    }

    @Override
    protected PayoutStatementStore store() {
        return persistence.payoutStatementStore();
    }

    @Override
    protected AgentSettlementStore settlements() {
        return persistence.agentSettlementStore();
    }

    @Override
    protected <T> T inTx(Supplier<T> work) {
        return persistence.inTx(work);
    }

    @Test
    void 정산_행_없이는_명세를_남길_수_없다() {
        PayoutService.PayoutStatement st = new PayoutService.PayoutStatement(CloseYm.of("202611"), 1, RecipientType.AGENT, "A-NONE",
                Money.won(1), Money.ZERO, Money.ZERO, Money.won(1));
        assertThatThrownBy(() -> inTx(() -> {
            store().save(st, OffsetDateTime.now(ZoneOffset.UTC));
            return null;
        })).hasStackTraceContaining("ORA-01400");
    }

    @Test
    void 같은_정산_행에_두_번_남길_수_없다() {
        CloseYm ym = CloseYm.of("202611");
        PayoutService.PayoutStatement st = new PayoutService.PayoutStatement(ym, 1, RecipientType.AGENT, "A-TWICE",
                Money.won(1), Money.ZERO, Money.ZERO, Money.won(1));
        assertThatThrownBy(() -> inTx(() -> {
            settlements().saveAll(List.of(new SettlementRow(ym, 1, RecipientType.AGENT, "A-TWICE", Money.won(1), Money.ZERO,
                    Money.ZERO, Money.won(1), List.of())));
            store().save(st, OffsetDateTime.now(ZoneOffset.UTC));
            store().save(st, OffsetDateTime.now(ZoneOffset.UTC));
            return null;
        })).hasStackTraceContaining("UQ_PAYOUT_STATEMENT_SETTLEMENT");
    }
}
