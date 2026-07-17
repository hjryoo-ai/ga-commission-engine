package ga.comm.recon;

import ga.comm.domain.money.Money;
import ga.comm.inbound.InboundStatement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 매출측 대사 (설계서 §7 D-5~D-1): 보험사 명세 vs 자체 계산 기대치.
 * 차이는 유형화되어 관리 화면에 노출된다 — GA 매출 손실 방지의 핵심 기능.
 */
public class ReconService {

    public enum DiffType {
        /** 일치 */
        MATCH,
        /** 금액 불일치 — 요율 차이 의심 */
        AMOUNT_MISMATCH,
        /** 명세에만 존재 — 자체 계산 누락 또는 미인지 계약 */
        UNEXPECTED_STATEMENT,
        /** 기대치에만 존재 — 보험사 미지급/누락 (추심 대상) */
        MISSING_STATEMENT
    }

    public record ReconLine(DiffType type, String matchKey, Money statementAmount,
                            Money expectedAmount, Money diff) {
    }

    public record ReconReport(List<ReconLine> lines) {
        public List<ReconLine> byType(DiffType type) {
            return lines.stream().filter(l -> l.type() == type).toList();
        }

        public Money totalShortfall() {
            // GA가 덜 받은 금액: 기대 − 명세 (양수인 것만)
            return lines.stream()
                    .map(ReconLine::diff)
                    .filter(Money::isPositive)
                    .reduce(Money.ZERO, Money::plus);
        }

        public boolean clean() {
            return lines.stream().allMatch(l -> l.type() == DiffType.MATCH);
        }
    }

    public ReconReport reconcile(List<InboundStatement> statements, List<ExpectedRow> expected) {
        Map<String, InboundStatement> statementByKey = new LinkedHashMap<>();
        for (InboundStatement statement : statements) {
            String key = statement.policyNo().value() + ":" + statement.commType().value() + ":"
                    + (statement.installmentNo() == null ? "-" : statement.installmentNo());
            statementByKey.merge(key, statement, (a, b) -> new InboundStatement(
                    a.insurerCd(), a.statementYm(), a.policyNo(), a.productKey(), a.commType(),
                    a.installmentNo(), a.amount().plus(b.amount()), a.rawFields()));
        }

        List<ReconLine> lines = new ArrayList<>();
        Map<String, ExpectedRow> expectedByKey = new LinkedHashMap<>();
        for (ExpectedRow row : expected) {
            expectedByKey.put(row.matchKey(), row);
        }

        for (Map.Entry<String, ExpectedRow> entry : expectedByKey.entrySet()) {
            ExpectedRow exp = entry.getValue();
            InboundStatement statement = statementByKey.remove(entry.getKey());
            if (statement == null) {
                lines.add(new ReconLine(DiffType.MISSING_STATEMENT, entry.getKey(),
                        Money.ZERO, exp.amount(), exp.amount()));
            } else if (statement.amount().equals(exp.amount())) {
                lines.add(new ReconLine(DiffType.MATCH, entry.getKey(),
                        statement.amount(), exp.amount(), Money.ZERO));
            } else {
                lines.add(new ReconLine(DiffType.AMOUNT_MISMATCH, entry.getKey(),
                        statement.amount(), exp.amount(),
                        exp.amount().minus(statement.amount())));
            }
        }

        for (Map.Entry<String, InboundStatement> entry : statementByKey.entrySet()) {
            lines.add(new ReconLine(DiffType.UNEXPECTED_STATEMENT, entry.getKey(),
                    entry.getValue().amount(), Money.ZERO,
                    entry.getValue().amount().negate()));
        }
        return new ReconReport(List.copyOf(lines));
    }
}
