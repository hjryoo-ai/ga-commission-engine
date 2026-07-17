package ga.comm.clawback;

import ga.comm.clawback.fixture.InMemoryClawbackReceivableStore;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 환수 채권화·자동 상계 (설계서 §6.3). */
class ClawbackOffsetServiceTest {

    private static final AgentId AGENT = new AgentId("A-1001");

    private InMemoryClawbackReceivableStore store;
    private ClawbackOffsetService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryClawbackReceivableStore();
        service = new ClawbackOffsetService(store);
    }

    @Test
    @DisplayName("당월 순지급이 음수면 부족분이 채권화된다")
    void 음수_순지급_채권화() {
        // 당월: 지급 100,000 − 환수 700,000 = −600,000
        ClawbackReceivable receivable = service.capitalize(AGENT, Money.won(-600_000), 55L);

        assertThat(receivable.amount()).isEqualTo(Money.won(600_000));
        assertThat(receivable.remaining()).isEqualTo(Money.won(600_000));
        assertThat(receivable.status()).isEqualTo(ReceivableStatus.OPEN);
    }

    @Test
    void 양수_순지급은_채권화_거부() {
        assertThatThrownBy(() -> service.capitalize(AGENT, Money.won(100), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이후 지급에서 오래된 채권부터 자동 상계된다")
    void 이후_지급에서_자동_상계() {
        service.capitalize(AGENT, Money.won(-600_000), 55L);
        service.capitalize(AGENT, Money.won(-100_000), 56L);

        // 다음 달 지급 가능액 500,000 → 첫 채권에서 500,000 상계
        Money offset1 = service.consume(AGENT, Money.won(500_000), 100L);
        assertThat(offset1).isEqualTo(Money.won(500_000));

        // 그 다음 달 300,000 → 첫 채권 잔액 100,000 + 둘째 채권 100,000 = 200,000 상계
        Money offset2 = service.consume(AGENT, Money.won(300_000), 101L);
        assertThat(offset2).isEqualTo(Money.won(200_000));

        assertThat(store.all()).allSatisfy(r ->
                assertThat(r.status()).isEqualTo(ReceivableStatus.CLOSED));
        assertThat(store.offsetHistory()).hasSize(3);
    }

    @Test
    @DisplayName("다른 설계사 채권과는 상계되지 않는다")
    void 설계사별_분리() {
        service.capitalize(AGENT, Money.won(-600_000), 55L);

        Money offset = service.consume(new AgentId("A-9999"), Money.won(500_000), 100L);
        assertThat(offset).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("대손 처리된 채권은 상계 대상에서 제외된다")
    void 대손_채권_제외() {
        ClawbackReceivable receivable = service.capitalize(AGENT, Money.won(-600_000), 55L);
        receivable.writeOff();

        assertThat(service.consume(AGENT, Money.won(500_000), 100L)).isEqualTo(Money.ZERO);
    }
}
