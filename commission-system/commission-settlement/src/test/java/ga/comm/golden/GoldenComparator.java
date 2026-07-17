package ga.comm.golden;

import ga.comm.calc.store.CommCalcRecord;
import ga.comm.deferral.ScheduleEntry;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.PolicyNo;
import ga.comm.limit.LimitLedger;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 기대 vs 실제 비교 — 불일치를 <b>정산 담당자가 읽을 수 있는 형태</b>로 낸다(설계서 §8.1).
 * 각 불일치는 [무엇을 / 어느 케이스 좌표에서 / 기대 얼마 vs 실제 얼마 / 차이]와 원인 후보를 담는다.
 */
final class GoldenComparator {

    private final GoldenCase gc;
    private final GoldenEngine engine;

    GoldenComparator(GoldenCase gc, GoldenEngine engine) {
        this.gc = gc;
        this.engine = engine;
    }

    /** 불일치 목록. 비어 있으면 케이스 통과. */
    List<String> mismatches() {
        List<String> diffs = new ArrayList<>();
        compareLines(diffs);
        compareLedgers(diffs);
        compareSchedule(diffs);
        return diffs;
    }

    private void compareLines(List<String> diffs) {
        for (GoldenCase.ExpectedLine exp : gc.expectedLines) {
            List<CommCalcRecord> pool = "NET".equalsIgnoreCase(exp.eventRef())
                    ? engine.allRecords()
                    : engine.recordsFor(exp.eventRef());

            if (!"NET".equalsIgnoreCase(exp.eventRef()) && !engine.hasRef(exp.eventRef())) {
                diffs.add("[LINE] event=" + exp.eventRef() + " — 타임라인에 그 ref의 산출물이 없습니다"
                        + " (ref 오타 또는 그 단계가 레코드를 만들지 않음)");
                continue;
            }

            List<CommCalcRecord> group = pool.stream()
                    .filter(r -> r.recipientType().name().equals(exp.recipientType()))
                    .filter(r -> r.recipientId().equals(exp.recipientId()))
                    .filter(r -> r.commType().value().equals(exp.commType()))
                    .toList();

            String coord = "event=" + exp.eventRef() + " " + exp.recipientType() + "/"
                    + exp.recipientId() + "/" + exp.commType();
            if (group.isEmpty()) {
                diffs.add("[LINE] " + coord + " — 기대한 라인이 실제 산출물에 없습니다 (기대 "
                        + won(exp.calcAmount()) + "). 원인 후보: 수급자/유형 표기 불일치, 룰 미적용,"
                        + " 한도로 전액 삭감되어 0원 라인이 필터됨");
                continue;
            }
            long actualCalc = group.stream().mapToLong(r -> r.calcAmount().toLong()).sum();
            if (actualCalc != exp.calcAmount()) {
                diffs.add("[LINE] " + coord + " calcAmount: 기대 " + won(exp.calcAmount())
                        + " vs 실제 " + won(actualCalc) + " (차이 " + won(actualCalc - exp.calcAmount())
                        + "). 원인 후보: 요율/지급률 시드값, 한도 삭감, 반올림 정책");
            }
            if (exp.limitCut() != null) {
                long actualCut = group.stream().mapToLong(r -> r.limitCutAmt().toLong()).sum();
                if (actualCut != exp.limitCut()) {
                    diffs.add("[LINE] " + coord + " limitCut: 기대 " + won(exp.limitCut())
                            + " vs 실제 " + won(actualCut) + " (차이 " + won(actualCut - exp.limitCut())
                            + "). 원인 후보: 한도(월납×배수)·초년도 윈도우·기지급 누적");
                }
            }
            if (exp.closeYm() != null) {
                String actualYm = group.stream().map(r -> r.closeYm().value()).distinct()
                        .collect(Collectors.joining("|"));
                if (!exp.closeYm().equals(actualYm)) {
                    diffs.add("[LINE] " + coord + " closeYm(귀속월): 기대 " + exp.closeYm()
                            + " vs 실제 " + actualYm + ". 원인 후보: 업무 발생일 기준 귀속, 마감월 차단");
                }
            }
        }
    }

    private void compareLedgers(List<String> diffs) {
        for (GoldenCase.ExpectedLedger exp : gc.expectedLedgers) {
            LimitLedger ledger = engine.ledgerFor(new PolicyNo(exp.policyNo()), new AgentId(exp.agentId()));
            String coord = "[LEDGER] " + exp.policyNo() + "/" + exp.agentId();
            if (ledger == null) {
                diffs.add(coord + " — 원장이 생성되지 않았습니다. 원인 후보: 한도룰 apply_from(계약일 기준"
                        + " 미적용), features에 LIMIT 누락");
                continue;
            }
            checkAmount(diffs, coord + " limitAmount", exp.limitAmount(), ledger.limitAmount().toLong());
            checkAmount(diffs, coord + " accumPaid", exp.accumPaid(), ledger.accumPaid().toLong());
            checkAmount(diffs, coord + " available", exp.available(), ledger.available().toLong());
            if (exp.fyStart() != null && !exp.fyStart().equals(ledger.fyStart())) {
                diffs.add(coord + " fyStart: 기대 " + exp.fyStart() + " vs 실제 " + ledger.fyStart());
            }
            if (exp.fyEnd() != null && !exp.fyEnd().equals(ledger.fyEnd())) {
                diffs.add(coord + " fyEnd: 기대 " + exp.fyEnd() + " vs 실제 " + ledger.fyEnd());
            }
        }
    }

    private void compareSchedule(List<String> diffs) {
        if (gc.expectedSchedule.isEmpty()) {
            return; // 스케줄 기대를 두지 않은 케이스는 검사하지 않는다
        }
        List<String> actual = new ArrayList<>(engine.schedule().stream().map(GoldenComparator::key).toList());
        List<String> expected = gc.expectedSchedule.stream().map(GoldenComparator::key).toList();
        for (String e : expected) {
            if (!actual.remove(e)) {
                diffs.add("[SCHEDULE] 기대 엔트리가 실제에 없습니다: " + e
                        + ". 원인 후보: 분급 커브 포인트·반올림 잔여 흡수·도래 상태 전이");
            }
        }
        for (String leftover : actual) {
            diffs.add("[SCHEDULE] 기대에 없는 실제 엔트리: " + leftover);
        }
    }

    private static void checkAmount(List<String> diffs, String label, Long expected, long actual) {
        if (expected != null && expected != actual) {
            diffs.add(label + ": 기대 " + won(expected) + " vs 실제 " + won(actual)
                    + " (차이 " + won(actual - expected) + ")");
        }
    }

    private static String key(ScheduleEntry e) {
        return "dueYm=" + e.dueYm().value() + " amount=" + won(e.amount().toLong())
                + " status=" + e.status() + " cond=" + e.payCondition();
    }

    private static String key(GoldenCase.ExpectedSchedule e) {
        return "dueYm=" + e.dueYm() + " amount=" + won(e.amount())
                + " status=" + e.status() + " cond=" + e.payCondition();
    }

    private static String won(long v) {
        return String.format("%,d원", v);
    }
}
