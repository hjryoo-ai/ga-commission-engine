package ga.comm.api.disclosure;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.RecipientType;

import java.util.List;

/**
 * 판매수수료 비교공시의 <b>서식 무관 원천 집계</b> (설계서 §1, Phase 15).
 *
 * <p>공시 서식·집계 단위·제출 주기는 아직 외부 확정 사안이다(§11 #13). 그래서 추출 계층은 서식을
 * 가정하지 않고 <b>가장 잔 차원</b>(마감월×보험사×상품×수급자×유형)의 순액 figure만 낸다. 어떤 서식이
 * 오든 {@link DisclosureFormat}(매핑 계층)이 이 figure를 롤업·피벗해 그 서식으로 만든다 —
 * 서식이 바뀌어도 추출 계층은 손대지 않는다.
 *
 * <p>모든 순액은 {@code NetAmountCalculator}(전 상태 합산, §3.0)로 산출된다 — 여기서 상태 필터 SUM은 없다.
 */
public record DisclosureAggregate(List<CloseYm> periods, List<Figure> figures) {

    /** 최소 집계 단위의 순액 한 건. */
    public record Figure(CloseYm closeYm, InsurerCode insurerCd, ProductKey productKey,
                         RecipientType recipientType, String recipientId, CommTypeCode commType,
                         Money net) {
    }
}
