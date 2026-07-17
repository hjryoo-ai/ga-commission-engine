package ga.comm.inbound.contract;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.inbound.InboundStatement;
import ga.comm.inbound.InboundStatementStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** InboundStatementStore 계약 테스트 (설계서 §8.7) — statementKey 멱등 저장. */
public abstract class InboundStatementStoreContract {

    protected static final InsurerCode SAMLIFE = new InsurerCode("SAMLIFE");

    protected abstract InboundStatementStore store();

    protected <T> T inTx(Supplier<T> work) {
        return work.get();
    }

    private InboundStatement statement(String policy, Integer installment, long amount) {
        return new InboundStatement(SAMLIFE, CloseYm.of("202608"), new PolicyNo(policy),
                new ProductKey("WHOLE-LIFE-20Y"), CommTypeCode.FY_COMM, installment,
                Money.won(amount), Map.of("원문회차", installment == null ? "" : installment.toString()));
    }

    @Test
    void 신규_명세만_저장되고_중복_키는_건너뛴다() {
        int first = inTx(() -> store().saveAll(List.of(
                statement("POL-IN-1", 1, 210_000),
                statement("POL-IN-1", 2, 45_000))));
        int second = inTx(() -> store().saveAll(List.of(
                statement("POL-IN-1", 2, 45_000),     // 중복
                statement("POL-IN-1", 3, 45_000))));  // 신규

        assertThat(first).isEqualTo(2);
        assertThat(second).isEqualTo(1);
        assertThat(inTx(() -> store().findByYmAndInsurer(CloseYm.of("202608"), SAMLIFE))).hasSize(3);
    }

    @Test
    void 명세_필드가_왕복_보존된다() {
        InboundStatement original = statement("POL-IN-2", null, 1_000_000);
        inTx(() -> store().saveAll(List.of(original)));

        List<InboundStatement> reloaded = inTx(() ->
                store().findByYmAndInsurer(CloseYm.of("202608"), SAMLIFE)).stream()
                .filter(s -> s.policyNo().equals(new PolicyNo("POL-IN-2")))
                .toList();

        assertThat(reloaded).hasSize(1);
        InboundStatement s = reloaded.get(0);
        assertThat(s.productKey()).isEqualTo(original.productKey());
        assertThat(s.commType()).isEqualTo(original.commType());
        assertThat(s.installmentNo()).isNull();
        assertThat(s.amount()).isEqualTo(Money.won(1_000_000));
        // rawFields는 JSON CLOB 왕복 — 빈 문자열 값도 보존된다
        assertThat(s.rawFields()).isEqualTo(original.rawFields());
    }

    @Test
    void 다른_보험사_명세는_조회되지_않는다() {
        inTx(() -> store().saveAll(List.of(statement("POL-IN-3", 1, 100_000))));

        assertThat(inTx(() -> store().findByYmAndInsurer(CloseYm.of("202608"), new InsurerCode("HANHWA"))))
                .isEmpty();
    }
}
