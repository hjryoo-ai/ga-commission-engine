package ga.comm.shadow;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.RecipientType;

import java.util.ArrayList;
import java.util.List;

/**
 * 외부 정산 결과 CSV 어댑터 (설계서 §8.5) — 기존 시스템/수기 엑셀에서 내린 CSV를 {@link ExternalSettlementRow}로
 * 읽는다. SAMLIFE 명세 어댑터(§8~9)와 같은 규약: 헤더 1줄 건너뜀, 열 수·값 이상은 <b>조용히 건너뛰지 않고</b>
 * {@link ShadowParseException}으로 실패한다(섀도 런의 대조 신뢰성은 입력 완전성에 달려 있다).
 *
 * <p>포맷: {@code 수급유형,수급자ID,수수료유형,마감월,금액} — 예 {@code AGENT,A-1001,FY_COMM,202608,1890000}.
 * 금액은 정수 원.
 */
public final class ShadowCsvAdapter {

    private static final int COLUMNS = 5;

    public List<ExternalSettlementRow> parse(String rawContent) {
        List<ExternalSettlementRow> rows = new ArrayList<>();
        String[] lines = rawContent.replace("﻿", "").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || i == 0) {
                continue; // 헤더 1줄 + 빈 줄 건너뜀
            }
            int lineNo = i + 1;
            String[] c = line.split(",", -1);
            if (c.length != COLUMNS) {
                throw new ShadowParseException(lineNo, line, "열 수는 " + COLUMNS + "이어야 합니다: " + c.length);
            }
            try {
                rows.add(new ExternalSettlementRow(
                        RecipientType.valueOf(c[0].strip()),
                        c[1].strip(),
                        new CommTypeCode(c[2].strip()),
                        CloseYm.of(c[3].strip()),
                        Money.won(Long.parseLong(c[4].strip()))));
            } catch (RuntimeException e) {
                throw new ShadowParseException(lineNo, line, e.getMessage());
            }
        }
        return rows;
    }

    /** 파싱 실패 — 조용한 누락 금지(어느 줄이 왜 실패했는지 남긴다). */
    public static final class ShadowParseException extends RuntimeException {
        public ShadowParseException(int lineNo, String line, String reason) {
            super("외부 정산 CSV " + lineNo + "행 파싱 실패: " + reason + " — [" + line + "]");
        }
    }
}
