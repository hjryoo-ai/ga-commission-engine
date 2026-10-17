package ga.comm.outbox.feed;

import com.fasterxml.jackson.databind.node.ObjectNode;
import ga.comm.calc.fixture.CalcTestHarness;
import ga.comm.calc.fixture.InMemoryAgentDirectory;
import ga.comm.outbox.contract.PortalContract;
import ga.comm.outbox.fixture.InMemoryAgentMasterReader;
import ga.comm.outbox.fixture.InMemoryOutboxStore;
import ga.comm.outbox.fixture.ValidatingOutboxStore;
import ga.comm.outbox.publish.OutboxPublisher;
import ga.comm.rule.model.OrgLevel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AgentSnapshot(D-8, P10) — 소속은 엔진의 해석(orgChainOf)을 위에서 아래로, 없는 값(역할·마스킹 이름)은 싣지 않는다. */
class AgentSnapshotPublisherTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-05T09:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    private final InMemoryOutboxStore outbox = new InMemoryOutboxStore();
    private final InMemoryAgentDirectory directory = CalcTestHarness.standardDirectory();
    private final InMemoryAgentMasterReader masters = new InMemoryAgentMasterReader()
            .with(new AgentMasterReader.AgentMaster("A-1001", "설계사A", LocalDate.of(2024, 3, 1), null), LocalDate.of(2025, 1, 1))
            .with(new AgentMasterReader.AgentMaster("A-2002", "설계사B", LocalDate.of(2024, 3, 1), LocalDate.of(2026, 8, 31)))
            .with(new AgentMasterReader.AgentMaster("A-3003", "해촉예정", LocalDate.of(2024, 3, 1), LocalDate.of(2026, 12, 31)));
    private final AgentSnapshotPublisher publisher = new AgentSnapshotPublisher(masters, directory,
            new OutboxPublisher(new ValidatingOutboxStore(outbox), CLOCK));

    @Test
    void 소속은_본부부터_팀까지_등급은_기준일_이력() {
        ObjectNode p = publisher.payload("A-1001", AS_OF);

        assertThat(PortalContract.get().validatePayload("AgentSnapshot", p)).isEmpty();
        assertThat(p.path("orgPath").asText()).isEqualTo("/H1/B1/T1");
        assertThat(p.path("grade").asText()).isEqualTo("SR");
        assertThat(p.path("name").asText()).isEqualTo("설계사A");
        assertThat(p.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(p.path("validFrom").asText()).isEqualTo("2025-01-01");   // 위촉일보다 늦은 이력 시작
        assertThat(p.has("role")).isFalse();
        assertThat(p.has("nameMasked")).isFalse();
        assertThat(AgentSnapshotPublisher.topDownLevels()).containsExactly(OrgLevel.HQ, OrgLevel.BRANCH, OrgLevel.TEAM);
    }

    @Test
    void 해촉은_기준일로_판정하고_해촉일이_validFrom() {
        directory.withOrgChain(new ga.comm.domain.id.AgentId("A-2002"), LocalDate.of(2024, 3, 1), InMemoryAgentDirectory.team("T9"));
        directory.withOrgChain(new ga.comm.domain.id.AgentId("A-3003"), LocalDate.of(2024, 3, 1), InMemoryAgentDirectory.team("T9"));

        ObjectNode dismissed = publisher.payload("A-2002", AS_OF);
        assertThat(dismissed.path("status").asText()).isEqualTo("DISMISSED");
        assertThat(dismissed.path("validFrom").asText()).isEqualTo("2026-08-31");
        assertThat(dismissed.has("grade")).isFalse();   // 등급 이력 없음 → 생략(계약 v1.1)
        assertThat(PortalContract.get().validatePayload("AgentSnapshot", dismissed)).isEmpty();

        ObjectNode future = publisher.payload("A-3003", AS_OF);
        assertThat(future.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(future.path("validFrom").asText()).isEqualTo("2024-03-01");
    }

    @Test
    void 소속이_없으면_실패하고_발행은_집합_AGENT로() {
        assertThatThrownBy(() -> publisher.publish("A-2002", AS_OF)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("소속");
        assertThatThrownBy(() -> publisher.publish("A-NONE", AS_OF)).isInstanceOf(IllegalArgumentException.class);

        publisher.publish("A-1001", AS_OF);
        outbox.assignPending(10);
        var stored = outbox.readAssignedAfter(0, 10);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).event().aggregateKind()).isEqualTo("AGENT");
        assertThat(stored.get(0).event().aggregateId()).isEqualTo("A-1001");
        assertThat(publisher.agentIds()).isEqualTo(List.of("A-1001", "A-2002", "A-3003"));
    }
}
