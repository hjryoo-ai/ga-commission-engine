package ga.comm.outbox.scenario;

import ga.comm.domain.time.CloseYm;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 포털 부록 A-1 시나리오의 입력(E2 ⓪) — 한 계약의 신계약(시책 정액 포함) → 마감 → 지급 → 해약을 값으로 둔다. 시각은 사실마다의 업무 시각(Q9 단계 시계).
 * 같은 값을 인메모리 시험({@code PortalA1StreamTest})과 로컬 스택 실행기(⑤)가 쓴다 — 시드 SQL이 {@code RuleFixtures.standardRules()}와 같으면
 * 두 실행이 같은 바이트를 낸다(C8).
 *
 * @param insurerCd·productKey 시드 룰의 키(요율이 이 둘로 정해진다)
 * @param elapsedInstallments 해약 시점 경과 회차(환수율 구간)
 * @param estimateYm 두 번째 판의 잠정 명세 귀속월(해약 뒤 — Q8)
 */
public record A1Scenario(String agentId, String policyNo, String insurerCd, String productKey, long monthlyPremium, LocalDate contractDate,
                         long incentiveAmount, CloseYm closeYm, int payoutRunSeq, LocalDate cancelDate, int elapsedInstallments,
                         CloseYm estimateYm, Times times) {

    /** 사실마다의 업무 시각(+09:00). ⓪a 첫 판이 포털에 들어간 뒤에는 바꾸지 않는다(E2 승인 Q9). */
    public record Times(OffsetDateTime calc, OffsetDateTime close, OffsetDateTime payout, OffsetDateTime cancel, OffsetDateTime estimate) {
        public Times {
            Objects.requireNonNull(calc);
            Objects.requireNonNull(close);
            Objects.requireNonNull(payout);
            Objects.requireNonNull(cancel);
            Objects.requireNonNull(estimate);
        }
    }

    public A1Scenario {
        Objects.requireNonNull(agentId);
        Objects.requireNonNull(policyNo);
        Objects.requireNonNull(insurerCd);
        Objects.requireNonNull(productKey);
        Objects.requireNonNull(contractDate);
        Objects.requireNonNull(closeYm);
        Objects.requireNonNull(cancelDate);
        Objects.requireNonNull(estimateYm);
        Objects.requireNonNull(times);
    }

    /** 단계 시계의 다섯 시각(E2 계획 Q9 — 포털 샘플의 시각에 맞춘 것). */
    public static final Times STEP_TIMES = new Times(
            OffsetDateTime.parse("2026-08-01T09:00:00+09:00"),
            OffsetDateTime.parse("2026-09-05T18:00:00+09:00"),
            OffsetDateTime.parse("2026-09-10T10:00:00+09:00"),
            OffsetDateTime.parse("2027-02-15T09:30:00+09:00"),
            OffsetDateTime.parse("2027-02-16T02:10:00+09:00"));

    /**
     * 포털 정본 A-1(E2 ⓪, 포털 Phase 1 수용 심사 §3-3): 월납 150,000·2026-08-01 체결·시책 1,000,000 → 한도 1,800,000을 넘어 삭감이 일어난다.
     * 2026-09 마감·지급, 2027-02-15 해약 7회차.
     */
    public static A1Scenario portal150000() {
        return new A1Scenario("A-1001", "POL-2026-0001", "SAMLIFE", "WHOLE-LIFE-20Y", 150_000, LocalDate.of(2026, 8, 1),
                1_000_000, CloseYm.of("202608"), 1, LocalDate.of(2027, 2, 15), 7, CloseYm.of("202702"), STEP_TIMES);
    }

    /** 첫 판 — 해약까지(잠정 명세 없음). */
    public static final String FIRST_EDITION = "a1_portal_150000";
    /** 두 번째 판 — 첫 판 + 해약 뒤 잠정 명세(Q8). */
    public static final String SECOND_EDITION = "a1_portal_150000_e2";
}
