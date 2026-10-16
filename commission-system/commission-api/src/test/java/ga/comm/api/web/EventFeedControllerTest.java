package ga.comm.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import ga.comm.calc.fixture.EventFixtures;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.type.EventType;
import ga.comm.outbox.OutboxSequencer;
import ga.comm.outbox.contract.PortalContract;
import ga.comm.outbox.feed.EventFeed;
import ga.comm.outbox.fixture.InMemoryOutboxStore;
import ga.comm.outbox.publish.OutboxPublisher;
import ga.comm.outbox.publish.PortalPayloads;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E1 C7 — 피드 API(MockMvc standalone, 엔진 시험 관례). 응답은 포털 계약 사본의 {@code FeedResponse}·{@code Problem} 스키마로 검증한다.
 * 부여는 호출마다 최대 N건(lazy) — 부여 상한 너머의 행은 아직 나오지 않는다(미부여 행 비노출). 401은 BootSmokeIT(실 보안 체인)가 본다.
 */
class EventFeedControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-05T09:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final EventFeed.Tx DIRECT = new EventFeed.Tx() {
        @Override
        public <T> T run(java.util.function.Supplier<T> work) {
            return work.get();
        }
    };

    private final InMemoryOutboxStore store = new InMemoryOutboxStore();
    private final OutboxPublisher publisher = new OutboxPublisher(store, CLOCK);

    private MockMvc mvc(int assignBatch) {
        EventFeed feed = new EventFeed(store, new OutboxSequencer(store, assignBatch), DIRECT, DIRECT, "T1");
        return MockMvcBuilders.standaloneSetup(new EventFeedController(feed))
                .setMessageConverters(new ByteArrayHttpMessageConverter())
                .build();
    }

    private void publish(int n) {
        for (int i = 0; i < n; i++) {
            var e = EventFixtures.terminal(EventType.CANCEL, new PolicyNo("POL-" + i), EventFixtures.CONTRACT_DATE,
                    LocalDate.of(2026, 9, 10), 2);
            publisher.publish("PolicyEventObserved", "POLICY", "POL-" + i, PortalPayloads.policyEventObserved(e).orElseThrow());
        }
    }

    private static JsonNode feedResponse(MockMvc mvc, String query) throws Exception {
        byte[] body = mvc.perform(get("/internal/v1/events" + query)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        JsonNode json = JsonMapper.builder().build().readTree(body);
        assertThat(PortalContract.get().validateApiSchema("FeedResponse", json)).as(json.toString()).isEmpty();
        return json;
    }

    private static JsonNode problem(MockMvc mvc, String query, int status) throws Exception {
        byte[] body = mvc.perform(get("/internal/v1/events" + query)).andExpect(status().is(status))
                .andReturn().getResponse().getContentAsByteArray();
        JsonNode json = JsonMapper.builder().build().readTree(body);
        assertThat(PortalContract.get().validateApiSchema("Problem", json)).as(json.toString()).isEmpty();
        return json;
    }

    @Test
    void 빈_피드() throws Exception {
        JsonNode r = feedResponse(mvc(500), "?afterSeq=0");
        assertThat(r.path("tenantId").asText()).isEqualTo("T1");
        assertThat(r.path("events")).isEmpty();
        assertThat(r.path("nextSeq").asLong()).isZero();
        assertThat(r.path("headSeq").asLong()).isZero();
        assertThat(r.path("schemaVersion").asInt()).isEqualTo(1);
    }

    @Test
    void 부여_상한_너머의_행은_다음_호출에서_이어진_번호로_나온다() throws Exception {
        publish(3);
        MockMvc mvc = mvc(2);

        JsonNode first = feedResponse(mvc, "?afterSeq=0");
        assertThat(first.path("events")).extracting(e -> e.path("seq").asLong()).containsExactly(1L, 2L);
        assertThat(first.path("nextSeq").asLong()).isEqualTo(2);
        assertThat(first.path("headSeq").asLong()).isEqualTo(2);   // 세 번째 행은 아직 순번이 없다 — 나오지 않는다

        JsonNode second = feedResponse(mvc, "?afterSeq=2");
        assertThat(second.path("events")).extracting(e -> e.path("seq").asLong()).containsExactly(3L);
        assertThat(second.path("events").get(0).path("payload").path("policyNo").asText()).isEqualTo("POL-2");
        assertThat(second.path("nextSeq").asLong()).isEqualTo(3);
        assertThat(second.path("headSeq").asLong()).isEqualTo(3);

        JsonNode caughtUp = feedResponse(mvc, "?afterSeq=3");
        assertThat(caughtUp.path("events")).isEmpty();
        assertThat(caughtUp.path("nextSeq").asLong()).isEqualTo(3);   // 빈 응답은 afterSeq 그대로
    }

    @Test
    void limit와_기본값_200() throws Exception {
        publish(201);
        MockMvc mvc = mvc(1000);
        JsonNode one = feedResponse(mvc, "?afterSeq=0&limit=1");
        assertThat(one.path("events")).hasSize(1);
        assertThat(one.path("nextSeq").asLong()).isEqualTo(1);
        assertThat(one.path("headSeq").asLong()).isEqualTo(201);
        assertThat(feedResponse(mvc, "?afterSeq=0").path("events")).hasSize(200);
        assertThat(feedResponse(mvc, "?afterSeq=0&limit=1000").path("events")).hasSize(201);
    }

    @Test
    void 헤드를_넘는_afterSeq는_422() throws Exception {
        publish(1);
        JsonNode p = problem(mvc(500), "?afterSeq=2", 422);
        assertThat(p.path("code").asText()).isEqualTo("AFTER_BEYOND_HEAD");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?afterSeq=", "?afterSeq=-1", "?afterSeq=abc", "?afterSeq=0&limit=0", "?afterSeq=0&limit=1001",
            "?afterSeq=0&limit=x", "?afterSeq=01", "?afterSeq=99999999999999999999"})
    void 잘못된_파라미터는_400(String query) throws Exception {
        assertThat(problem(mvc(500), query, 400).path("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void 테넌트_형식이_틀리면_기동_실패() {
        assertThatThrownBy(() -> new EventFeed(store, new OutboxSequencer(store, 1), DIRECT, DIRECT, "t1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new EventFeed(store, new OutboxSequencer(store, 1), DIRECT, DIRECT, null))
                .isInstanceOf(IllegalStateException.class);
    }
}
