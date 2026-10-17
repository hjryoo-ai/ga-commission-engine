package ga.comm.infra.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import ga.comm.domain.time.CloseYm;
import ga.comm.infra.OraclePersistence;
import ga.comm.outbox.feed.AgentMasterReader;
import ga.comm.outbox.feed.EventFeed;
import ga.comm.outbox.feed.StatementQuery;
import ga.comm.outbox.fixture.InMemoryAgentMasterReader;
import ga.comm.outbox.fixture.OutboxEngine;
import ga.comm.settlement.WithholdingTaxPolicy;
import ga.comm.settlement.estimate.MonthClosedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E2 C6 — 실 Oracle에서 명세 조회 {@code CONFIRMED}는 같은 달의 발행 이벤트와 필드 단위로 같다(StatementConfirmed 전 필드 + 지급 뒤
 * PayoutCompleted의 withholdingTax·net). 마감 전 CONFIRMED는 {@code NotConfirmed}(404), 마감월 ESTIMATE는 {@code MonthClosedException}(422).
 */
class StatementQueryOracleIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final OraclePersistence p = OracleTestSupport.persistence();
    private final JdbcTemplate jdbc = new JdbcTemplate(OracleTestSupport.dataSource());
    private OutboxEngine e;
    private StatementQuery query;

    @BeforeEach
    void setUp() {
        OracleTestSupport.cleanAll();
        e = DryRunScenario.engine(p);
        DryRunScenario.seed(p, e);
        EventFeed.Tx tx = new EventFeed.Tx() {
            @Override
            public <T> T run(java.util.function.Supplier<T> work) {
                return p.inTx(work);
            }
        };
        AgentMasterReader agents = new InMemoryAgentMasterReader()
                .with(new AgentMasterReader.AgentMaster("A-1001", "허구설계사", LocalDate.of(2020, 1, 1), null));
        query = new StatementQuery(e.estimator, e.calcs, e.stores.adjustments(), p.settleCloseStore(), p.payoutStatementStore(), agents,
                p.outboxStore(), tx, WithholdingTaxPolicy.STANDARD_3_3, Clock.system(ZoneId.of("Asia/Seoul")));
    }

    /** 이 시험이 만든 이벤트 가운데 그 달의 마지막 {@code type} payload(아웃박스는 시험 사이에 지워지지 않는다 — 최근 것이 이번 시나리오). */
    private JsonNode lastPayload(String type, String ym) throws Exception {
        List<String> payloads = jdbc.queryForList("SELECT payload FROM OUTBOX_EVENT WHERE event_type = ? ORDER BY event_no", String.class, type);
        JsonNode found = null;
        for (String s : payloads) {
            JsonNode n = JSON.readTree(s);
            if (n.path("closeYm").asText().equals(ym)) {
                found = n;
            }
        }
        return found;
    }

    @Test
    void CONFIRMED는_발행된_이벤트와_같다() throws Exception {
        for (String ym : List.of("202608", "202609")) {
            JsonNode q = query.confirmed("A-1001", CloseYm.of(ym));
            JsonNode confirmed = lastPayload("StatementConfirmed", ym);
            JsonNode paid = lastPayload("PayoutCompleted", ym);
            for (String f : List.of("agentId", "closeYm", "gross", "clawbackOffset", "adjustment", "closedAt")) {
                assertThat(q.path(f).asText()).as(ym + " " + f).isEqualTo(confirmed.path(f).asText());   // 값 대조(파서의 정수 노드 폭은 다를 수 있다)
            }
            assertThat(q.path("withholdingTax").asLong()).as(ym).isEqualTo(paid.path("withholdingTax").asLong());
            assertThat(q.path("net").asLong()).as(ym).isEqualTo(paid.path("net").asLong());
            assertThat(q.has("net") && paid.has("net")).isTrue();
        }
    }

    @Test
    void 마감_전_CONFIRMED와_마감월_ESTIMATE는_거부된다() {
        assertThatThrownBy(() -> query.confirmed("A-1001", DryRunScenario.OPEN_MONTH)).isInstanceOf(StatementQuery.NotConfirmed.class);
        assertThatThrownBy(() -> query.estimate("A-1001", CloseYm.of("202608"))).isInstanceOf(MonthClosedException.class);
        assertThat(query.estimate("A-1001", DryRunScenario.OPEN_MONTH).path("closeYm").asText()).isEqualTo("202610");
    }
}
