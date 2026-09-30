package ga.comm.disclosure.grade.snapshot;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ga.comm.disclosure.grade.GradeResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * 응답 정규 직렬화 — RFC 8785 JCS(키 정렬·공백 없음). 발급 시 <b>한 번</b> 만들어 저장하고, POST 응답 본문과 GET 재조회 본문이
 * 모두 이 바이트다(재직렬화 없음). UNAVAILABLE 항목은 6개 필드를 쓰지 않는다(부재, null 출력 금지).
 */
public final class SnapshotJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SnapshotJson() {
    }

    public static String canonical(GradeSnapshot s) {
        ObjectNode root = JSON.createObjectNode();
        root.put("snapshotId", s.snapshotId());
        root.put("gradingPolicyVersionId", s.gradingPolicyVersionId());
        root.put("rankingPolicyVersionId", s.rankingPolicyVersionId());
        root.put("tieBreak", s.tieBreak().name());
        ObjectNode basis = root.putObject("basis");
        basis.put("groupAvgSource", s.basis().groupAvgSource());
        basis.put("period", s.basis().period());
        basis.put("groupPopulation", s.basis().groupPopulation());
        ArrayNode results = root.putArray("results");
        for (GradeResult r : s.results()) {
            ObjectNode item = results.addObject();
            item.put("productKey", r.productKey());
            switch (r) {
                case GradeResult.Ok ok -> {
                    item.put("status", "OK");
                    item.put("ratioToAvg", ok.ratioToAvg());   // 문자열 그대로 — number로 쓰지 않는다
                    item.put("grade", ok.grade());
                    item.put("gradeLabel", ok.gradeLabel());
                    item.put("gradeOrdinal", ok.gradeOrdinal());
                    item.put("rankInSet", ok.rankInSet());
                    item.put("tie", ok.tie());
                }
                case GradeResult.Unavailable u -> {
                    item.put("status", "UNAVAILABLE");
                    item.put("reason", u.reason());
                }
            }
        }
        root.put("generatedAt", s.generatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        try {
            return Jcs.canonicalize(JSON.writeValueAsString(root));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 기본 JSON(검증용 파싱). */
    public static JsonMapper mapper() {
        return JSON;
    }

    public static String sha256Hex(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
