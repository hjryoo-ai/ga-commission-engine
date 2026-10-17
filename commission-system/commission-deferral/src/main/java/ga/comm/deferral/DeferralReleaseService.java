package ga.comm.deferral;

import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.CalcStatus;
import ga.comm.domain.type.RecipientType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 분급 도래 배치 (설계서 §6.2) — 도래분(due_ym=당월) 중 지급 조건 충족 건을 RELEASED로
 * 전환하고 DEFERRED 계산 레코드를 만들어 지급 파이프라인에 투입한다.
 * 조건 미충족 건은 HELD로 보류한다. 멱등: 상태 전이가 재실행을 자연 차단한다.
 *
 * <p>마감 배치(②)에 연결할 때는 settlement 배선에서 CloseHook으로 감싼다 (모듈 순환 방지).
 */
public class DeferralReleaseService {

    private final DeferralScheduleStore scheduleStore;
    private final CommCalcStore calcStore;
    private final PolicyStatusProvider policyStatus;

    public DeferralReleaseService(DeferralScheduleStore scheduleStore, CommCalcStore calcStore,
                                  PolicyStatusProvider policyStatus) {
        this.scheduleStore = Objects.requireNonNull(scheduleStore);
        this.calcStore = Objects.requireNonNull(calcStore);
        this.policyStatus = Objects.requireNonNull(policyStatus);
    }

    public record ReleaseResult(List<CommCalcRecord> released, int held) {
    }

    public ReleaseResult release(CloseYm ym) {
        List<CommCalcRecord> released = new ArrayList<>();
        int held = 0;

        for (ScheduleEntry entry : scheduleStore.findDue(ym)) {
            if (!releasable(entry, ym, policyStatus)) {
                scheduleStore.replace(entry.held(), DeferralTransitionReason.POLICY_NOT_INFORCE);
                held++;
                continue;
            }
            CommCalcRecord source = calcStore.findById(entry.sourceCalcId())
                    .orElseThrow(() -> new IllegalStateException(
                            "분급 원본 계산이 없습니다: " + entry.sourceCalcId()));

            CommCalcRecord record = calcStore.insert(new CommCalcRecord(
                    null, source.eventId(), entry.policyNo(), RecipientType.AGENT,
                    entry.agentId().value(), CommTypeCode.DEFERRED,
                    entry.amount(), null, entry.amount(), Money.ZERO,
                    ym, CalcStatus.CALCULATED, null,
                    "[{\"type\":\"DEFERRAL_CURVE\",\"key\":\"curveId=" + entry.curveVersionId()
                            + "\",\"ref\":\"scheduleId=" + entry.scheduleId() + "\"}]",
                    "[{\"step\":\"DEFERRAL_RELEASE_V1\",\"msg\":\"분급 도래 지급\","
                            + "\"values\":{\"sourceCalcId\":\"" + entry.sourceCalcId()
                            + "\",\"dueYm\":\"" + entry.dueYm() + "\"}}]"));
            scheduleStore.replace(entry.released(record.calcId()), DeferralTransitionReason.DUE_RELEASED);
            released.add(record);
        }
        return new ReleaseResult(released, held);
    }

    /**
     * 도래분 하나가 이번 달 지급 대상인가(유지 조건) — 도래 처리와 잠정 명세의 도래 미리보기(E2 P1)가 같은 판정을 쓴다. 대상이면 그 달에
     * {@code entry.amount()}의 DEFERRED 레코드 하나가 생기고, 아니면 HELD(금액 0).
     */
    public static boolean releasable(ScheduleEntry entry, CloseYm ym, PolicyStatusProvider policyStatus) {
        return policyStatus.isInforce(entry.policyNo(), ym);
    }

    /** 해약/실효/철회 시 잔여(SCHEDULED) 스케줄 소멸. */
    public int cancelRemaining(ga.comm.domain.id.PolicyNo policyNo, ga.comm.domain.id.AgentId agentId) {
        int cancelled = 0;
        for (ScheduleEntry entry : scheduleStore.findByPolicyAndAgent(policyNo, agentId)) {
            if (entry.status() == ScheduleStatus.SCHEDULED) {
                scheduleStore.replace(entry.cancelled(), DeferralTransitionReason.POLICY_TERMINATED);
                cancelled++;
            }
        }
        return cancelled;
    }
}
