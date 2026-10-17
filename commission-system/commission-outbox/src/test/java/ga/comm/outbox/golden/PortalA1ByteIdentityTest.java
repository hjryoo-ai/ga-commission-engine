package ga.comm.outbox.golden;

import ga.comm.outbox.contract.PortalContract;
import ga.comm.outbox.scenario.A1Scenario;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2 P17 — 포털 계약 v1.3의 A-1 샘플({@code samples/a1/a1_portal_150000.jsonl}, 사본 @ {@code UPSTREAM})이 엔진 첫 판과 <b>바이트로 같다</b>.
 * E1의 {@code PortalA1ComparisonTest}(같은 입력을 돌려 차이 표 + 타입 순서를 대조 — 포털 샘플이 계약 예시였을 때)를 대신한다: 포털이 엔진
 * 스트림을 그대로 들였으므로 대조는 차이 표가 아니라 바이트 일치다(E1 수용 심사 §3-3). 세 바이트 열 — 포털 사본, 엔진 골든 파일, 지금 다시 돌린
 * 시나리오 — 이 모두 같아야 한다.
 */
class PortalA1ByteIdentityTest {

    @Test
    void 포털_A1_샘플은_엔진_첫_판과_바이트가_같다() throws IOException {
        byte[] portal = PortalContract.portalBytes("events/v1/samples/a1/" + A1Scenario.FIRST_EDITION + ".jsonl");
        byte[] golden = Files.readAllBytes(PortalA1StreamTest.snapshotDir().resolve(A1Scenario.FIRST_EDITION + ".jsonl"));
        List<String> regenerated = PortalA1StreamTest.firstEdition();
        assertThat(portal).as("포털 사본 = 엔진 골든 파일").isEqualTo(golden);
        assertThat(new String(golden, StandardCharsets.UTF_8)).as("엔진 골든 파일 = 지금 돌린 시나리오").isEqualTo(String.join("\n", regenerated) + "\n");
        assertThat(PortalContract.sampleNames()).as("포털 a1/에는 그 파일 하나뿐").filteredOn(n -> n.startsWith("a1/"))
                .containsExactly("a1/" + A1Scenario.FIRST_EDITION + ".jsonl");
    }
}
