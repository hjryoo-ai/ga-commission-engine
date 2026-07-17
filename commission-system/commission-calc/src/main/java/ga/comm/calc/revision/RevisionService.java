package ga.comm.calc.revision;

import ga.comm.calc.CommissionCalculator;
import ga.comm.calc.store.CloseStatusProvider;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 정정·재계산 — Reversal &amp; Rebook (설계서 §6.4).
 *
 * <p>규약:
 * <ul>
 *   <li>원본은 REVERSED로 마킹될 뿐 합산에서 빠지지 않는다 — 음수 reversal 레코드가 상쇄한다.</li>
 *   <li>reversal의 귀속월: 원본 월이 미마감이면 원본 월, 마감이면 이후 첫 OPEN 월.
 *       과거 마감 숫자는 절대 바뀌지 않는다.</li>
 *   <li>멱등: 이미 REVERSED인 원본은 다시 reversal을 만들지 않는다. rebook을 두 번 돌려도
 *       수급자×유형별 순액은 동일하다(값 멱등). 배치 계층은 요청 ID로 레코드 수준 중복도 차단한다.</li>
 * </ul>
 */
public class RevisionService {

    /** reversal 생성 직후 후속 처리 (한도 원장 원복 등). */
    public interface ReversalHook {
        void onReversal(CommCalcRecord original, CommCalcRecord reversal);
    }

    private final CommCalcStore calcStore;
    private final CloseStatusProvider closeStatus;
    private final List<ReversalHook> hooks;

    public RevisionService(CommCalcStore calcStore, CloseStatusProvider closeStatus,
                           List<ReversalHook> hooks) {
        this.calcStore = Objects.requireNonNull(calcStore);
        this.closeStatus = Objects.requireNonNull(closeStatus);
        this.hooks = List.copyOf(hooks);
    }

    /**
     * 단건 취소분개. 이미 REVERSED면 아무것도 하지 않는다(멱등).
     *
     * @return 생성된 reversal 레코드 (멱등 skip이면 empty)
     */
    public Optional<CommCalcRecord> reverse(long calcId, String reason) {
        CommCalcRecord original = calcStore.findById(calcId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 calcId: " + calcId));
        if (original.status() == CalcStatus.REVERSED) {
            return Optional.empty();
        }
        if (original.reversalOf() != null) {
            throw new IllegalArgumentException(
                    "reversal 레코드는 다시 취소할 수 없습니다: calcId=" + calcId);
        }

        CloseYm attribution = closeStatus.attributionFor(original.closeYm());
        CommCalcRecord reversal = calcStore.insert(new CommCalcRecord(
                null, original.eventId(), original.policyNo(),
                original.recipientType(), original.recipientId(), original.commType(),
                original.baseAmount(), original.appliedRate(),
                original.calcAmount().negate(), ga.comm.domain.money.Money.ZERO,
                attribution, CalcStatus.CALCULATED, original.calcId(),
                original.ruleVersions(),
                "[{\"step\":\"REVERSAL\",\"msg\":" + ga.comm.calc.util.JsonLite.quoteForTrace(reason)
                        + ",\"values\":{\"originalCalcId\":\"" + original.calcId() + "\"}}]"));

        calcStore.transition(original.calcId(), CalcStatus.REVERSED);
        for (ReversalHook hook : hooks) {
            hook.onReversal(original, reversal);
        }
        return Optional.of(reversal);
    }

    /**
     * 이벤트 전체 재계산: 활성 세대(비reversal·비REVERSED) 전부 reversal 후 새 룰로 rebook.
     * 활성 세대가 없으면(reversal까지 끝나고 rebook 전에 중단된 재시도) reversal 없이 rebook만 한다.
     */
    public RebookResult rebookEvent(PolicyEvent event, CommissionCalculator calculator, String reason) {
        if (event.eventId() == null) {
            throw new IllegalArgumentException("저장된 이벤트만 재계산할 수 있습니다");
        }

        List<CommCalcRecord> reversals = new ArrayList<>();
        for (CommCalcRecord record : calcStore.findByEventId(event.eventId())) {
            if (record.reversalOf() == null && record.status() != CalcStatus.REVERSED) {
                reverse(record.calcId(), reason).ifPresent(reversals::add);
            }
        }

        List<CommCalcRecord> rebooked = calculator.rebook(event);
        return new RebookResult(reversals, rebooked);
    }

    public record RebookResult(List<CommCalcRecord> reversals, List<CommCalcRecord> rebooked) {
    }
}
