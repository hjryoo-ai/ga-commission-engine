package ga.comm.settlement.simulation;

import ga.comm.calc.RuleVersionRef;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** E2 ②·C4 — {@code ruleSnapshotId}(승인 Q2): 같은 묶음 같은 값, 순서·중복 무관, 버전 하나만 달라도 다른 값, 정의 그대로의 바이트. */
class RuleSnapshotIdTest {

    private static final RuleVersionRef RATE = new RuleVersionRef("COMM_RATE", "FY_COMM", "rateId=1,v=1");
    private static final RuleVersionRef LIMIT = new RuleVersionRef("LIMIT_RULE", "GA_TO_AGENT", "ruleId=1");
    private static final RuleVersionRef CURVE = new RuleVersionRef("DEFERRAL_CURVE", "curveId=1", "v=1");

    @Test
    void 같은_묶음은_순서와_중복에_무관하게_같은_값이다() {
        assertThat(RuleSnapshotId.of(List.of(RATE, LIMIT, CURVE)))
                .isEqualTo(RuleSnapshotId.of(List.of(CURVE, RATE, LIMIT, RATE)))
                .matches("^RS-[0-9a-f]{32}$");
    }

    @Test
    void 버전_하나만_달라도_다른_값이다() {
        assertThat(RuleSnapshotId.of(List.of(RATE, LIMIT)))
                .isNotEqualTo(RuleSnapshotId.of(List.of(new RuleVersionRef("COMM_RATE", "FY_COMM", "rateId=1,v=2"), LIMIT)))
                .isNotEqualTo(RuleSnapshotId.of(List.of(RATE)));
    }

    @Test
    void 정의는_정렬한_줄의_SHA_256_앞_32자다() throws Exception {
        String lines = "COMM_RATE|FY_COMM|rateId=1,v=1\nLIMIT_RULE|GA_TO_AGENT|ruleId=1";
        String hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(lines.getBytes(StandardCharsets.UTF_8)));
        assertThat(RuleSnapshotId.of(List.of(LIMIT, RATE))).isEqualTo("RS-" + hex.substring(0, 32));
    }
}
