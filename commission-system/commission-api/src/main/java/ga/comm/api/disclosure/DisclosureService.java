package ga.comm.api.disclosure;

import ga.comm.calc.net.NetAmountCalculator;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.calc.store.PolicyEventStore;
import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.RecipientType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 판매수수료 비교공시 <b>추출 계층</b> (설계서 §1, Phase 15). COMM_CALC에서 기간별 순액 figure를
 * 뽑되 <b>순액은 전부 {@link NetAmountCalculator}</b>(전 상태 합산, §3.0)로 산출한다 — 상태 필터
 * SUM은 쓰지 않는다(공시도 마감 전 정정에서 이중 차감이 나면 안 되므로).
 *
 * <p>보험사·상품은 COMM_CALC에 없고 원천 이벤트(POLICY_EVENT)에 있으므로 event_id로 조인해 태깅한다.
 * 서식 매핑은 이 계층의 관심사가 아니다 — {@link DisclosureFormat}가 담당한다.
 */
public class DisclosureService {

    private final CommCalcStore calcStore;
    private final PolicyEventStore eventStore;

    public DisclosureService(CommCalcStore calcStore, PolicyEventStore eventStore) {
        this.calcStore = Objects.requireNonNull(calcStore);
        this.eventStore = Objects.requireNonNull(eventStore);
    }

    /** 주어진 마감월들의 최소 단위 순액 figure를 낸다. */
    public DisclosureAggregate extract(List<CloseYm> periods) {
        Map<GroupKey, List<CommCalcRecord>> byGroup = new LinkedHashMap<>();
        Map<Long, PolicyEvent> eventCache = new HashMap<>();

        for (CloseYm period : periods) {
            for (CommCalcRecord record : calcStore.findByCloseYm(period)) {
                PolicyEvent event = eventCache.computeIfAbsent(record.eventId(),
                        id -> eventStore.findById(id).orElse(null));
                if (event == null) {
                    continue; // 이벤트 원본이 없는 레코드는 보험사·상품 태깅 불가 — 공시 대상에서 제외
                }
                GroupKey key = new GroupKey(period, event.insurerCd(), event.productKey(),
                        record.recipientType(), record.recipientId(), record.commType());
                byGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
            }
        }

        List<DisclosureAggregate.Figure> figures = new ArrayList<>();
        byGroup.forEach((k, records) -> figures.add(new DisclosureAggregate.Figure(
                k.closeYm, k.insurerCd, k.productKey, k.recipientType, k.recipientId, k.commType,
                NetAmountCalculator.netOf(records)))); // ★ 순액은 전 상태 합산으로만
        return new DisclosureAggregate(List.copyOf(periods), List.copyOf(figures));
    }

    private record GroupKey(CloseYm closeYm, InsurerCode insurerCd, ProductKey productKey,
                            RecipientType recipientType, String recipientId, CommTypeCode commType) {
    }
}
