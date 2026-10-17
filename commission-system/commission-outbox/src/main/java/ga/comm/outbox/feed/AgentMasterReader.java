package ga.comm.outbox.feed;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 설계사 마스터 읽기 포트(AgentSnapshot 부트스트랩, E1 P8·P10) — 구현은 infra(Oracle {@code AGENT_MST}·이력). 등급·소속은 엔진의
 * {@code AgentDirectory}(수수료 귀속과 같은 해석)로 읽고, 이 포트는 마스터 행과 이력 시작일만 준다.
 */
public interface AgentMasterReader {

    record AgentMaster(String agentId, String name, LocalDate appointedOn, LocalDate dismissedOn) {
    }

    /** 전 설계사 ID(오름차순). */
    List<String> agentIds();

    Optional<AgentMaster> find(String agentId);

    /** {@code asOf}에 유효한 등급·소속 이력 가운데 가장 늦은 {@code apply_from}(없으면 empty). */
    Optional<LocalDate> latestHistoryFrom(String agentId, LocalDate asOf);
}
