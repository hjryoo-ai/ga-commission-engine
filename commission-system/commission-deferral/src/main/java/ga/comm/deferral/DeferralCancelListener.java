package ga.comm.deferral;

import ga.comm.calc.CalcContext;
import ga.comm.calc.CalcResultListener;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.type.EventType;

import java.util.List;
import java.util.Objects;

/** 해약/실효/철회 이벤트 처리 직후 잔여 분급 스케줄을 CANCELLED로 소멸시킨다 (설계서 §2.3). */
public class DeferralCancelListener implements CalcResultListener {

    private final DeferralReleaseService releaseService;

    public DeferralCancelListener(DeferralReleaseService releaseService) {
        this.releaseService = Objects.requireNonNull(releaseService);
    }

    @Override
    public void onPersisted(CalcContext ctx, List<PersistedLine> lines) {
        EventType type = ctx.event().eventType();
        if (!ctx.target().isAgent()
                || (type != EventType.CANCEL && type != EventType.LAPSE && type != EventType.WITHDRAW)) {
            return;
        }
        releaseService.cancelRemaining(ctx.event().policyNo(),
                new AgentId(ctx.target().recipient().id()));
    }
}
