package ga.comm.outbox.feed;

import com.fasterxml.jackson.databind.node.ObjectNode;
import ga.comm.calc.recipient.AgentDirectory;
import ga.comm.calc.recipient.OrgAssignment;
import ga.comm.domain.id.AgentId;
import ga.comm.outbox.publish.OutboxPublisher;
import ga.comm.rule.model.OrgLevel;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * {@code AgentSnapshot}(D-8) — 부트스트랩 잡({@code outboxAgentSnapshotJob})이 설계사마다 한 트랜잭션으로 부른다. 엔진에는 마스터 쓰기 경로가
 * 없으므로(시드는 SQL·CSV) 변경 시점 발행은 없다. 값:
 * <ul>
 *   <li>{@code name} = {@code agent_name}, {@code grade} = 기준일 등급(이력이 없으면 생략), {@code role}·{@code nameMasked} 없음(엔진에 없다).</li>
 *   <li>{@code orgPath} = {@link AgentDirectory#orgChainOf}(수수료 귀속과 같은 해석)를 위에서 아래로({@code /HQ/BRANCH/TEAM}). 소속이 없으면
 *       계약이 받지 않으므로 실패한다(잡이 그 설계사를 격리·보고).</li>
 *   <li>{@code status} = 기준일에 해촉됐으면 {@code DISMISSED}, 아니면 {@code ACTIVE}.</li>
 *   <li>{@code validFrom} = 위촉일·기준일 유효 등급/소속 이력 시작일·(해촉이면) 해촉일 가운데 가장 늦은 날.</li>
 * </ul>
 */
public final class AgentSnapshotPublisher {

    public static final String TYPE = "AgentSnapshot";
    public static final String KIND = "AGENT";
    private static final Comparator<OrgAssignment> TOP_DOWN =
            Comparator.comparingInt((OrgAssignment o) -> o.level().ordinal()).reversed();

    private final AgentMasterReader masters;
    private final AgentDirectory directory;
    private final OutboxPublisher publisher;

    public AgentSnapshotPublisher(AgentMasterReader masters, AgentDirectory directory, OutboxPublisher publisher) {
        this.masters = Objects.requireNonNull(masters);
        this.directory = Objects.requireNonNull(directory);
        this.publisher = Objects.requireNonNull(publisher);
    }

    public List<String> agentIds() {
        return masters.agentIds();
    }

    public void publish(String agentId, LocalDate asOf) {
        publisher.publish(TYPE, KIND, agentId, payload(agentId, asOf));
    }

    ObjectNode payload(String agentId, LocalDate asOf) {
        AgentMasterReader.AgentMaster m = masters.find(agentId)
                .orElseThrow(() -> new IllegalArgumentException("설계사 마스터가 없다: " + agentId));
        AgentId id = new AgentId(agentId);
        List<OrgAssignment> chain = directory.orgChainOf(id, asOf);
        if (chain.isEmpty()) {
            throw new IllegalStateException("기준일 " + asOf + "에 소속이 없다 — 계약 orgPath를 만들 수 없다: " + agentId);
        }
        StringBuilder orgPath = new StringBuilder();
        chain.stream().sorted(TOP_DOWN).forEach(o -> orgPath.append('/').append(o.orgId().value()));
        boolean dismissed = m.dismissedOn() != null && !m.dismissedOn().isAfter(asOf);
        LocalDate validFrom = Stream.of(m.appointedOn(), masters.latestHistoryFrom(agentId, asOf).orElse(null),
                        dismissed ? m.dismissedOn() : null)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElseThrow();

        ObjectNode p = OutboxPublisher.payload();
        p.put("agentId", agentId);
        p.put("name", m.name());
        directory.gradeOf(id, asOf).ifPresent(g -> p.put("grade", g));
        p.put("orgPath", orgPath.toString());
        p.put("status", dismissed ? "DISMISSED" : "ACTIVE");
        p.put("validFrom", validFrom.toString());
        return p;
    }

    /** HQ > BRANCH > TEAM 순서가 {@link OrgLevel} 선언 순서의 역순이라는 전제 — 어휘가 바뀌면 시험이 잡는다. */
    static List<OrgLevel> topDownLevels() {
        return Stream.of(OrgLevel.values()).sorted(Comparator.comparingInt(OrgLevel::ordinal).reversed()).toList();
    }
}
