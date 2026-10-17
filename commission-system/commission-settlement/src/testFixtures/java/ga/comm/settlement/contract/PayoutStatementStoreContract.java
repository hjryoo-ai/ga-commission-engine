package ga.comm.settlement.contract;

import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.RecipientType;
import ga.comm.settlement.AgentSettlementStore;
import ga.comm.settlement.PayoutService;
import ga.comm.settlement.PayoutStatementStore;
import ga.comm.settlement.SettlementRow;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** PayoutStatementStore 계약 스위트(PAYOUT_STATEMENT, Phase E1 P6) — 정산 행을 남긴 같은 트랜잭션에서 명세를 저장·왕복. */
public abstract class PayoutStatementStoreContract {

    protected abstract PayoutStatementStore store();

    protected abstract AgentSettlementStore settlements();

    protected <T> T inTx(Supplier<T> work) {
        return work.get();
    }

    private void settle(SettlementRow row, PayoutService.PayoutStatement statement, OffsetDateTime paidAt) {
        inTx(() -> {
            settlements().saveAll(List.of(row));
            store().save(statement, paidAt);
            return null;
        });
    }

    @Test
    void 지급명세가_세액_순액_지급시각까지_왕복된다() {
        CloseYm ym = CloseYm.of("202611");
        OffsetDateTime paidAt = OffsetDateTime.of(2026, 12, 10, 10, 0, 0, 0, ZoneOffset.ofHours(9));
        PayoutService.PayoutStatement st = new PayoutService.PayoutStatement(ym, 1, RecipientType.AGENT, "A-PS1",
                Money.won(1_770_000), Money.won(53_100), Money.won(5_310), Money.won(1_711_590));
        settle(new SettlementRow(ym, 1, RecipientType.AGENT, "A-PS1", Money.won(1_890_000), Money.won(120_000),
                Money.ZERO, Money.won(1_770_000), List.of()), st, paidAt);

        List<PayoutStatementStore.Saved> saved = inTx(() -> store().findByCloseYm(ym));
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).statement()).isEqualTo(st);
        assertThat(saved.get(0).paidAt().toInstant()).isEqualTo(paidAt.toInstant());
    }

    @Test
    void 런_회차별로_따로_남고_다른_달은_조회되지_않는다() {
        CloseYm ym = CloseYm.of("202612");
        OffsetDateTime paidAt = OffsetDateTime.of(2027, 1, 10, 10, 0, 0, 0, ZoneOffset.ofHours(9));
        for (int run = 1; run <= 2; run++) {
            PayoutService.PayoutStatement st = new PayoutService.PayoutStatement(ym, run, RecipientType.AGENT, "A-PS2",
                    Money.won(100_000L * run), Money.won(3_000L * run), Money.won(300L * run), Money.won(96_700L * run));
            settle(new SettlementRow(ym, run, RecipientType.AGENT, "A-PS2", Money.won(100_000L * run), Money.ZERO, Money.ZERO,
                    Money.won(100_000L * run), List.of()), st, paidAt.plusDays(run));
        }
        assertThat(inTx(() -> store().findByCloseYm(ym))).extracting(s -> s.statement().runSeq()).containsExactlyInAnyOrder(1, 2);
        assertThat(inTx(() -> store().findByCloseYm(CloseYm.of("202701")))).isEmpty();
    }
}
