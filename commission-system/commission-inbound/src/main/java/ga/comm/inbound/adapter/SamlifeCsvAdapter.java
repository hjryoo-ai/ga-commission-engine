package ga.comm.inbound.adapter;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.inbound.InboundStatement;
import ga.comm.inbound.InsurerStatementAdapter;
import ga.comm.inbound.StatementParseException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 파일럿 어댑터: SAMLIFE CSV 명세.
 *
 * <p>포맷(헤더 1행 + 데이터): {@code 증권번호,상품코드,수수료유형,회차,금액}
 * 보험사 유형 코드 → 표준 CommTypeCode 매핑은 데이터(생성자 파라미터)로 관리한다 —
 * 보험사가 코드를 추가해도 매핑 등록만으로 대응한다.
 */
public class SamlifeCsvAdapter implements InsurerStatementAdapter {

    private static final InsurerCode SAMLIFE = new InsurerCode("SAMLIFE");

    /** 기본 매핑 (예시): 01=초년도, 02=계속, 03=시책. */
    public static final Map<String, CommTypeCode> DEFAULT_TYPE_MAPPING = Map.of(
            "01", CommTypeCode.FY_COMM,
            "02", CommTypeCode.RENEWAL,
            "03", CommTypeCode.INCENTIVE
    );

    private final Map<String, CommTypeCode> typeMapping;

    public SamlifeCsvAdapter() {
        this(DEFAULT_TYPE_MAPPING);
    }

    public SamlifeCsvAdapter(Map<String, CommTypeCode> typeMapping) {
        this.typeMapping = Map.copyOf(typeMapping);
    }

    @Override
    public InsurerCode insurerCd() {
        return SAMLIFE;
    }

    @Override
    public List<InboundStatement> parse(CloseYm statementYm, String rawContent) {
        List<InboundStatement> rows = new ArrayList<>();
        String[] lines = rawContent.split("\r?\n");

        for (int i = 1; i < lines.length; i++) {  // 0행은 헤더
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] cols = line.split(",", -1);
            if (cols.length != 5) {
                throw new StatementParseException(i + 1, line, "컬럼 수 " + cols.length + " (기대 5)");
            }

            CommTypeCode commType = typeMapping.get(cols[2].trim());
            if (commType == null) {
                throw new StatementParseException(i + 1, line,
                        "미등록 수수료 유형 코드 '" + cols[2].trim() + "' — 매핑 등록 필요");
            }

            Integer installment;
            try {
                String raw = cols[3].trim();
                installment = raw.isEmpty() ? null : Integer.valueOf(raw);
            } catch (NumberFormatException e) {
                throw new StatementParseException(i + 1, line, "회차가 숫자가 아님");
            }

            Money amount;
            try {
                amount = Money.of(new BigDecimal(cols[4].trim()));
            } catch (RuntimeException e) {
                throw new StatementParseException(i + 1, line, "금액 파싱 실패: " + e.getMessage());
            }

            rows.add(new InboundStatement(SAMLIFE, statementYm,
                    new PolicyNo(cols[0].trim()), new ProductKey(cols[1].trim()),
                    commType, installment, amount,
                    Map.of("line", String.valueOf(i + 1))));
        }
        return rows;
    }
}
