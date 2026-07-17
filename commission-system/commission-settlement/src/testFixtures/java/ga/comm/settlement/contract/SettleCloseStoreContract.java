package ga.comm.settlement.contract;

import ga.comm.domain.time.CloseYm;
import ga.comm.settlement.SettleCloseStore;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** SettleCloseStore 계약 테스트 (설계서 §8.7) — 마감 상태 전이 왕복. */
public abstract class SettleCloseStoreContract {

    protected abstract SettleCloseStore store();

    protected <T> T inTx(Supplier<T> work) {
        return work.get();
    }

    @Test
    void 레코드가_없으면_OPEN이다() {
        assertThat(inTx(() -> store().stateOf(CloseYm.of("209912"))))
                .isEqualTo(SettleCloseStore.CloseState.OPEN);
    }

    @Test
    void 마감_전이가_왕복_보존된다() {
        CloseYm ym = CloseYm.of("202608");

        inTx(() -> {
            store().transition(ym, SettleCloseStore.CloseState.CLOSING, "정산담당");
            return null;
        });
        assertThat(inTx(() -> store().stateOf(ym))).isEqualTo(SettleCloseStore.CloseState.CLOSING);

        inTx(() -> {
            store().transition(ym, SettleCloseStore.CloseState.CLOSED, "정산담당");
            return null;
        });
        assertThat(inTx(() -> store().stateOf(ym))).isEqualTo(SettleCloseStore.CloseState.CLOSED);
    }

    @Test
    void 체크리스트_실패_시_OPEN_복귀가_가능하다() {
        CloseYm ym = CloseYm.of("202609");
        inTx(() -> {
            store().transition(ym, SettleCloseStore.CloseState.CLOSING, "정산담당");
            store().transition(ym, SettleCloseStore.CloseState.OPEN, "정산담당");
            return null;
        });

        assertThat(inTx(() -> store().stateOf(ym))).isEqualTo(SettleCloseStore.CloseState.OPEN);
    }

    @Test
    void 강제_마감_사유가_업무테이블에_영속된다() {
        CloseYm ym = CloseYm.of("202612");
        inTx(() -> {
            store().transition(ym, SettleCloseStore.CloseState.CLOSING, "정산담당");
            store().transition(ym, SettleCloseStore.CloseState.CLOSED, "팀장",
                    "보험사 지연분 익월 반영 승인");
            return null;
        });

        // 감사 대상 사실은 배치 메타가 아니라 업무 테이블이 정본 (§7)
        assertThat(inTx(() -> store().forceReasonOf(ym))).isEqualTo("보험사 지연분 익월 반영 승인");
    }

    @Test
    void 일반_마감은_사유가_없고_이후_전이가_기존_사유를_지우지_않는다() {
        CloseYm forced = CloseYm.of("202701");
        CloseYm normal = CloseYm.of("202702");
        inTx(() -> {
            // 일반 마감: 사유 없음
            store().transition(normal, SettleCloseStore.CloseState.CLOSED, "정산담당");
            // 강제 마감 후 (가상의) 후속 전이가 null 사유로 와도 기존 사유는 보존
            store().transition(forced, SettleCloseStore.CloseState.CLOSED, "팀장", "강제 사유");
            store().transition(forced, SettleCloseStore.CloseState.CLOSED, "정산담당", null);
            return null;
        });

        assertThat(inTx(() -> store().forceReasonOf(normal))).isNull();
        assertThat(inTx(() -> store().forceReasonOf(forced))).isEqualTo("강제 사유");
    }

    @Test
    void 월별_상태는_독립이다() {
        inTx(() -> {
            store().transition(CloseYm.of("202610"), SettleCloseStore.CloseState.CLOSED, "정산담당");
            return null;
        });

        assertThat(inTx(() -> store().stateOf(CloseYm.of("202611"))))
                .isEqualTo(SettleCloseStore.CloseState.OPEN);
    }
}
