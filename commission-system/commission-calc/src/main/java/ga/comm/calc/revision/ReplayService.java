package ga.comm.calc.revision;

import ga.comm.calc.CalcContext;
import ga.comm.calc.CalcLine;
import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.domain.event.PolicyEvent;

import java.util.Objects;

/**
 * 감사 재현(replay, 설계서 §8.6) — 임의 calc 레코드에 대해 원본 이벤트를 저장 없이
 * 재실행하고 동일 금액이 재산출되는지 검증한다.
 *
 * <p>전제: effective-dated 룰 저장소는 과거 기준일 조회 결과를 보존한다(버전 교체는 트리밍/SUPERSEDE로만).
 * 한도 게이트가 걸린 라인은 계산 시점의 원장 상태가 필요하므로, 호출 측이 LIMIT_LEDGER_DTL에서
 * 대상 calc 이전까지의 전기 합으로 원장을 재구성해 파이프라인에 물린다.
 */
public class ReplayService {

    private final CommissionCalculator calculator;

    public ReplayService(CommissionCalculator calculator) {
        this.calculator = Objects.requireNonNull(calculator);
    }

    public ReplayResult replay(PolicyEvent event, CommCalcRecord expected) {
        for (CalcContext ctx : calculator.dryRun(event)) {
            if (!ctx.target().recipient().type().equals(expected.recipientType())
                    || !ctx.target().recipient().id().equals(expected.recipientId())) {
                continue;
            }
            for (CalcLine line : ctx.lines()) {
                if (line.commType().equals(expected.commType())) {
                    boolean matches = line.amount().equals(expected.calcAmount())
                            && line.baseAmount().equals(expected.baseAmount());
                    return new ReplayResult(matches, line.amount(), expected.calcAmount(),
                            matches ? "재현 일치" : "재현 불일치 — 룰 소급 변경 또는 원장 상태 차이");
                }
            }
        }
        return new ReplayResult(false, null, expected.calcAmount(), "대상 라인이 재현되지 않음");
    }

    public record ReplayResult(boolean matches, ga.comm.domain.money.Money replayedAmount,
                               ga.comm.domain.money.Money expectedAmount, String detail) {
    }
}
