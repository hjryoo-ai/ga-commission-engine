package ga.comm.infra.report;

import ga.comm.domain.time.CloseYm;
import ga.comm.infra.mapper.CloseReportMapper;
import ga.comm.settlement.CloseReport;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 마감 리포트 3종의 Oracle 구현 (설계서 §7 Phase 11):
 * ① 룰 데이터 완결성 — 겹치는 ACTIVE 요율·시책(Ambiguous)·지급률 겹침·필수 마스터 누락(§6.6 3차 안전망, 부록 B-13 사전 검출),
 * ② MAXVALUE 파티션 적재 — 연 파티션 SPLIT 누락 안전망(§5.5),
 * ③ 승인 경합 감지 — ACTIVATE 직후 SUPERSEDE 근접 동시 승인 알림(§6.6).
 * 전부 비차단: 발견 항목은 마감 잡 실행 컨텍스트에 영속되어 관리자 후속 조치 대상이 된다.
 */
public final class OracleCloseReports {

    private OracleCloseReports() {
    }

    public static List<CloseReport> all(CloseReportMapper mapper, int contentionWindowMinutes) {
        return List.of(ruleCompleteness(mapper), maxvaluePartition(mapper),
                approvalContention(mapper, contentionWindowMinutes));
    }

    public static CloseReport ruleCompleteness(CloseReportMapper mapper) {
        Objects.requireNonNull(mapper);
        return new CloseReport() {
            @Override
            public String name() {
                return "룰 데이터 완결성";
            }

            @Override
            public List<String> findings(CloseYm closeYm) {
                List<String> findings = new ArrayList<>(mapper.overlappingActiveRates());
                findings.addAll(mapper.overlappingActiveIncentives());
                findings.addAll(mapper.overlappingPayoutRates());
                findings.addAll(mapper.missingCommTypeMaster(closeYm.value(), monthEnd(closeYm)));
                return findings;
            }
        };
    }

    public static CloseReport maxvaluePartition(CloseReportMapper mapper) {
        Objects.requireNonNull(mapper);
        return new CloseReport() {
            @Override
            public String name() {
                return "MAXVALUE 파티션 적재";
            }

            @Override
            public List<String> findings(CloseYm closeYm) {
                return mapper.maxvaluePartitionLoad();
            }
        };
    }

    public static CloseReport approvalContention(CloseReportMapper mapper, int windowMinutes) {
        Objects.requireNonNull(mapper);
        return new CloseReport() {
            @Override
            public String name() {
                return "승인 경합 감지";
            }

            @Override
            public List<String> findings(CloseYm closeYm) {
                return mapper.approvalContention(windowMinutes);
            }
        };
    }

    private static java.time.LocalDate monthEnd(CloseYm closeYm) {
        return YearMonth.parse(closeYm.value(), DateTimeFormatter.ofPattern("yyyyMM")).atEndOfMonth();
    }
}
