package ga.comm.outbox.publish;

import com.fasterxml.jackson.databind.JsonNode;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.EventType;
import ga.comm.outbox.fixture.CloseInvariant;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E1 C8 — 이벤트 쪽 마감 불변식(P11)과 지급 반복 무해. 마감 뒤 들어온 마감월 날짜의 사건은 다음 열린 달로 계산되고(리졸버), 확정된 명세 뒤에
 * 같은 달 계산·정정 이벤트가 없다. 같은 달 두 번째 지급 런은 새 대상이 없으면 이벤트도 없다.
 */
class CloseInvariantEventTest {

    @Test
    void 마감_뒤_마감월_사건은_다음_달로_귀속되고_불변식이_지켜진다() {
        OutboxScenario s = new OutboxScenario();
        s.calculator.process(EventFixtures.newContract());
        s.closeService.close(CloseYm.of("202608"), "정산담당");
        // 8월에 일어났지만 마감 뒤 들어온 2회차 입금, 8월 계산에 대한 정정
        s.calculator.process(EventFixtures.payment(2, LocalDate.of(2026, 8, 25)));
        long fy = Long.parseLong(s.payloads(OutboxCommCalcStore.TYPE).get(0).path("calcId").asText());
        s.revision.reverse(fy, "마감 뒤 정정");
        s.adjustmentService.adjust(fy, "마감 뒤 조정", Money.won(-10_000), "정산팀장");

        List<JsonNode> calcs = s.payloads(OutboxCommCalcStore.TYPE);
        assertThat(calcs.subList(1, calcs.size())).allSatisfy(p -> assertThat(p.path("closeYm").asText()).isEqualTo("202609"));
        assertThat(s.payloads(OutboxAdjustmentStore.TYPE)).allSatisfy(p -> assertThat(p.path("closeYm").asText()).isEqualTo("202609"));
        assertThat(CloseInvariant.violations(s.events())).isEmpty();
    }

    @Test
    void 위반을_찾는다() {
        var json = com.fasterxml.jackson.databind.json.JsonMapper.builder().build();
        List<JsonNode> events = List.of(
                json.createObjectNode().put("seq", 1).put("type", "StatementConfirmed")
                        .set("payload", json.createObjectNode().put("agentId", "A").put("closeYm", "202608")),
                json.createObjectNode().put("seq", 2).put("type", "CommissionCalculated")
                        .set("payload", json.createObjectNode().put("agentId", "A").put("closeYm", "202608")));
        assertThat(CloseInvariant.violations(events)).containsExactly("seq 2 CommissionCalculated → 이미 확정된 A:202608");
    }

    @Test
    void 같은_달_지급_런_반복은_이벤트를_더_내지_않는다() {
        OutboxScenario s = new OutboxScenario();
        s.calculator.process(EventFixtures.newContract());
        s.calculator.process(EventFixtures.terminal(EventType.CANCEL, EventFixtures.POLICY_1, EventFixtures.CONTRACT_DATE,
                LocalDate.of(2026, 8, 20), 1));
        s.closeService.close(CloseYm.of("202608"), "정산담당");
        s.payoutService.run(CloseYm.of("202608"), 1);
        int after = s.events().size();
        List<JsonNode> paid = s.payloads(PayoutPublisher.TYPE);

        s.payoutService.run(CloseYm.of("202608"), 2);
        s.payoutService.run(CloseYm.of("202608"), 1);

        assertThat(s.events()).hasSize(after);
        assertThat(s.payloads(PayoutPublisher.TYPE)).isEqualTo(paid).hasSize(1);
    }
}
