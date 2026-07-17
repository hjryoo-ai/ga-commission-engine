package ga.comm.settlement;

import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.calc.store.PolicyEventStore;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;
import ga.comm.limit.LimitLedger;
import ga.comm.limit.LimitLedgerStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 월마감 배치 (설계서 §7 D-Day):
 * ① 미처리 이벤트 0건 확인 → ② 훅 실행(분급 도래분 RELEASE 등) → ③ 1200% 한도 전수 검증 →
 * ④ CALCULATED→CONFIRMED 확정, SETTLE_CLOSE=CLOSED.
 *
 * <p>체크리스트가 전부 통과해야 CLOSED로 전이한다. 강제 마감은 권한+사유가 기록된다.
 *
 * <p>v1.1 §7: 상계(ClawbackOffsetService)·원천세·정산행 생성은 마감이 아니라
 * <b>지급 런</b>({@link PayoutService})의 일이다 — 지급 주기가 월 N회로 확정되어도
 * 마감 절차는 변하지 않는다 (마감 하드결합 금지, §6.3).
 */
public class MonthCloseService {

    private final CommCalcStore calcStore;
    private final PolicyEventStore eventStore;
    private final LimitLedgerStore ledgerStore;
    private final SettleCloseStore closeStore;
    private final List<CloseHook> hooks;

    public MonthCloseService(CommCalcStore calcStore, PolicyEventStore eventStore,
                             LimitLedgerStore ledgerStore, SettleCloseStore closeStore,
                             List<CloseHook> hooks) {
        this.calcStore = Objects.requireNonNull(calcStore);
        this.eventStore = Objects.requireNonNull(eventStore);
        this.ledgerStore = Objects.requireNonNull(ledgerStore);
        this.closeStore = Objects.requireNonNull(closeStore);
        this.hooks = List.copyOf(hooks);
    }

    public record CheckItem(String name, boolean passed, String detail) {
    }

    public record CloseResult(CloseYm closeYm, boolean closed, List<CheckItem> checklist) {
    }

    public CloseResult close(CloseYm ym, String closedBy) {
        return close(ym, closedBy, false, null);
    }

    /** 강제 마감은 사유 필수 — 체크리스트 실패를 덮어쓴 기록이 남는다. */
    public CloseResult close(CloseYm ym, String closedBy, boolean force, String forceReason) {
        if (closeStore.stateOf(ym) == SettleCloseStore.CloseState.CLOSED) {
            throw new IllegalStateException("이미 마감된 월입니다: " + ym);
        }
        if (force && (forceReason == null || forceReason.isBlank())) {
            throw new IllegalArgumentException("강제 마감은 사유가 필수입니다");
        }
        closeStore.transition(ym, SettleCloseStore.CloseState.CLOSING, closedBy);

        List<CheckItem> checks = new ArrayList<>();

        // ① 당월 이벤트 계산 완료 확인
        long unprocessed = eventStore.unprocessedCount();
        checks.add(new CheckItem("미처리 이벤트", unprocessed == 0, "PENDING/FAILED " + unprocessed + "건"));

        // ② 플러그인 훅 (분급 도래분 RELEASE 등)
        for (CloseHook hook : hooks) {
            try {
                checks.add(new CheckItem(hook.name(), true, hook.beforeConfirm(ym)));
            } catch (RuntimeException e) {
                checks.add(new CheckItem(hook.name(), false, "실패: " + e.getMessage()));
            }
        }

        // ③ 1200% 한도 원장 전수 검증 (불변식 위반 0건)
        List<LimitLedger> broken = ledgerStore.findAll().stream()
                .filter(l -> !l.invariantHolds())
                .toList();
        checks.add(new CheckItem("한도 원장 불변식", broken.isEmpty(),
                "위반 " + broken.size() + "건 / 전체 " + ledgerStore.findAll().size() + "건"));

        boolean allPassed = checks.stream().allMatch(CheckItem::passed);
        if (!allPassed && !force) {
            closeStore.transition(ym, SettleCloseStore.CloseState.OPEN, closedBy);
            return new CloseResult(ym, false, checks);
        }
        if (!allPassed) {
            checks.add(new CheckItem("강제 마감", true, closedBy + ": " + forceReason));
        }

        // ④ 확정: 당월 CALCULATED → CONFIRMED
        for (CommCalcRecord record : calcStore.findByCloseYm(ym)) {
            if (record.status() == CalcStatus.CALCULATED) {
                calcStore.transition(record.calcId(), CalcStatus.CONFIRMED);
            }
        }
        // 강제 마감이면 사유를 업무 테이블(SETTLE_CLOSE)에 정본으로 영속한다(v1.1.5 §7).
        closeStore.transition(ym, SettleCloseStore.CloseState.CLOSED, closedBy,
                force ? forceReason : null);
        return new CloseResult(ym, true, checks);
    }
}
