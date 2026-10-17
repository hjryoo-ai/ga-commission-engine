package ga.comm.infra.it;

import com.fasterxml.jackson.databind.json.JsonMapper;
import ga.comm.infra.OraclePersistence;
import ga.comm.outbox.fixture.OutboxEngine;
import ga.comm.settlement.simulation.SimulationModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2 C4 — 실제 Oracle 상태 위에서 같은 시뮬레이션을 고정 시계로 두 번 돌리면 결과가 같고 직렬화 바이트도 같다(오버레이 ID·순서·
 * {@code ruleSnapshotId}·{@code generatedAt}가 결정적). {@code ruleSnapshotId}는 시계에서 오지 않는다 — 단위 시험은 {@code RuleSnapshotIdTest}와
 * {@code DryRunSimulatorTest#같은_입력은_시계와_무관하게_같은_결과이고_generatedAt만_시계를_따른다}(주입 S2).
 */
class SimulationDeterminismIT {

    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

    private final OraclePersistence p = OracleTestSupport.persistence();
    private OutboxEngine e;

    @BeforeEach
    void setUp() {
        OracleTestSupport.cleanAll();
        e = DryRunScenario.engine(p);
        DryRunScenario.seed(p, e);
    }

    @Test
    void 고정_시계로_두_번_돌리면_결과와_바이트가_같다() throws Exception {
        List<Object> first = p.inTx(() -> DryRunScenario.simulateAll(DryRunScenario.simulator(e)));
        List<Object> second = p.inTx(() -> DryRunScenario.simulateAll(DryRunScenario.simulator(e)));

        assertThat(second).isEqualTo(first);
        assertThat(JSON.writeValueAsBytes(second)).isEqualTo(JSON.writeValueAsBytes(first));
        var newContract = (SimulationModel.NewContractResult) first.getFirst();
        assertThat(newContract.generatedAt()).isEqualTo("2026-11-02T02:10:00+09:00");
        assertThat(newContract.ruleSnapshotId()).matches("^RS-[0-9a-f]{32}$");
    }
}
