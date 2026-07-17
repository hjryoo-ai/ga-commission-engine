package ga.comm.golden;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 골든 CSV의 최소 리더 (Phase 14). 정산 담당자가 엑셀로 저장한 CSV를 그대로 읽는다:
 * <ul>
 *   <li>UTF-8 BOM 제거(엑셀이 붙이는 EF BB BF) — 첫 헤더가 깨지지 않게 한다.</li>
 *   <li>빈 줄과 {@code #}로 시작하는 주석 줄은 건너뛴다(작성 가이드 주석 허용).</li>
 *   <li>필드 구분자는 콤마, 값은 트림. 값 안의 콤마는 쓰지 않는다(부가 속성은 {@code ;}로 구분).</li>
 * </ul>
 *
 * <p>따옴표 escape 등 복잡한 CSV 방언은 의도적으로 지원하지 않는다 — 골든 케이스는 사람이 쓰고 읽는
 * 단순 표여야 하며, 콤마·따옴표가 필요한 자유 텍스트 값을 두지 않는 것이 스키마 규약이다.
 */
final class GoldenCsv {

    /** 헤더명 → 값. 없는 헤더 조회는 빈 문자열(누락과 빈 값을 동일 취급 — 선택 컬럼 생략 허용). */
    static final class Row {
        private final Map<String, String> cells;
        private final Path source;
        private final int lineNo;

        Row(Map<String, String> cells, Path source, int lineNo) {
            this.cells = cells;
            this.source = source;
            this.lineNo = lineNo;
        }

        String get(String header) {
            return cells.getOrDefault(header, "").trim();
        }

        /** 값이 비어 있으면 fallback. */
        String getOr(String header, String fallback) {
            String v = get(header);
            return v.isEmpty() ? fallback : v;
        }

        boolean has(String header) {
            return !get(header).isEmpty();
        }

        String where() {
            return source.getFileName() + ":" + lineNo;
        }
    }

    private GoldenCsv() {
    }

    /** 헤더 있는 표를 읽는다(첫 유효 줄=헤더). 파일이 없으면 빈 리스트. */
    static List<Row> readTable(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<String[]> lines = rawLines(file);
        if (lines.isEmpty()) {
            return List.of();
        }
        String[] header = lines.get(0);
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] cols = lines.get(i);
            Map<String, String> cells = new LinkedHashMap<>();
            for (int c = 0; c < header.length; c++) {
                cells.put(header[c].trim(), c < cols.length ? cols[c] : "");
            }
            rows.add(new Row(cells, file, i + 1));
        }
        return rows;
    }

    /** {@code key,value} 세로 표를 맵으로 읽는다(case.csv 용). */
    static Map<String, String> readKeyValue(Path file) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String[] cols : rawLines(file)) {
            if (cols.length >= 1 && cols[0].trim().equalsIgnoreCase("field")) {
                continue; // 헤더 줄(field,value) 건너뛰기
            }
            if (cols.length >= 1 && !cols[0].trim().isEmpty()) {
                map.put(cols[0].trim(), cols.length >= 2 ? cols[1].trim() : "");
            }
        }
        return map;
    }

    private static List<String[]> rawLines(Path file) {
        try {
            List<String[]> out = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                line = stripBom(line);
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                out.add(line.split(",", -1));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("골든 CSV를 읽지 못했습니다: " + file, e);
        }
    }

    private static String stripBom(String s) {
        return (!s.isEmpty() && s.charAt(0) == '﻿') ? s.substring(1) : s;
    }
}
