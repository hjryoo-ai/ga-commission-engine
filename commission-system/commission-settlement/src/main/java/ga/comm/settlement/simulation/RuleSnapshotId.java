package ga.comm.settlement.simulation;

import ga.comm.calc.RuleVersionRef;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.TreeSet;

/**
 * 시뮬레이션 결과의 {@code ruleSnapshotId}(E2 승인 Q2) — {@code "RS-"} + SHA-256(정렬·중복 제거한 {@code ruleType|key|versionRef} 줄을
 * {@code \n}으로 이은 UTF-8)의 앞 32자(소문자 16진). 범위는 <b>룰만</b>: 같은 룰 버전 묶음이면 같은 값이고, 금액의 재현에는 룰 + 원장·레코드
 * 상태가 필요하다(설계서 §6.8). 시계·요청 시각과 무관하다(주입 S2).
 */
public final class RuleSnapshotId {

    private RuleSnapshotId() {
    }

    public static String of(Collection<RuleVersionRef> refs) {
        TreeSet<String> lines = new TreeSet<>();
        for (RuleVersionRef r : refs) {
            lines.add(r.ruleType() + "|" + r.key() + "|" + r.versionRef());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            return "RS-" + HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
