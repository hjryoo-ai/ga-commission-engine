package ga.comm.settlement;

import ga.comm.deferral.DeferralReleaseService;
import ga.comm.domain.time.CloseYm;

import java.util.Objects;

/**
 * 마감 프로세스 ② "분급 도래분 RELEASE" 배선 (설계서 §7).
 * 도래분이 마감 확정(⑤) 전에 당월 계산 레코드로 편입되도록 CloseHook으로 감싼다.
 */
public class DeferralReleaseCloseHook implements CloseHook {

    private final DeferralReleaseService releaseService;

    public DeferralReleaseCloseHook(DeferralReleaseService releaseService) {
        this.releaseService = Objects.requireNonNull(releaseService);
    }

    @Override
    public String name() {
        return "분급 도래분 RELEASE";
    }

    @Override
    public String beforeConfirm(CloseYm closeYm) {
        DeferralReleaseService.ReleaseResult result = releaseService.release(closeYm);
        return "지급 투입 " + result.released().size() + "건, 보류(HELD) " + result.held() + "건";
    }
}
