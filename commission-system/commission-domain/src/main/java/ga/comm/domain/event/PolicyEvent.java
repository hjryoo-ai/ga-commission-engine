package ga.comm.domain.event;

import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;
import ga.comm.domain.type.EventType;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * 계약 이벤트 (원천, 불변). 모든 수수료 계산은 이 이벤트에서 파생된다.
 *
 * @param eventId        저장 후 부여되는 ID (저장 전 null)
 * @param eventKey       멱등키: 보험사코드+증권번호+유형+회차 등 자연키 조합. 중복 수신 차단.
 * @param policyNo       증권번호
 * @param insurerCd      보험사 코드
 * @param productKey     상품 키
 * @param eventType      이벤트 유형
 * @param eventDate      업무 발생일 — 요율 등 회차성 룰의 기준일
 * @param contractDate   계약 체결일 — 1200%룰/분급/환수 룰 버전의 기준일
 * @param agentId        모집 설계사
 * @param monthlyPremium 월납환산보험료 (한도 산정 기준)
 * @param paymentAmount  회차 납입액 (PAYMENT 이벤트, 그 외 null 허용)
 * @param installmentNo  회차 (해당 없는 이벤트는 null)
 * @param attributes     이벤트별 부가 속성 (예: REDUCE의 변경 후 보험료)
 */
public record PolicyEvent(
        Long eventId,
        String eventKey,
        PolicyNo policyNo,
        InsurerCode insurerCd,
        ProductKey productKey,
        EventType eventType,
        LocalDate eventDate,
        LocalDate contractDate,
        AgentId agentId,
        Money monthlyPremium,
        Money paymentAmount,
        Integer installmentNo,
        Map<String, String> attributes
) {
    public PolicyEvent {
        Objects.requireNonNull(eventKey, "eventKey");
        Objects.requireNonNull(policyNo, "policyNo");
        Objects.requireNonNull(insurerCd, "insurerCd");
        Objects.requireNonNull(productKey, "productKey");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(eventDate, "eventDate");
        Objects.requireNonNull(contractDate, "contractDate");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(monthlyPremium, "monthlyPremium");
        if (eventKey.isBlank() || eventKey.length() > 100) {
            throw new IllegalArgumentException("이벤트 키가 올바르지 않습니다: " + eventKey);
        }
        if (installmentNo != null && installmentNo < 1) {
            throw new IllegalArgumentException("회차는 1 이상이어야 합니다: " + installmentNo);
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public PolicyEvent withEventId(long id) {
        return new PolicyEvent(id, eventKey, policyNo, insurerCd, productKey, eventType, eventDate,
                contractDate, agentId, monthlyPremium, paymentAmount, installmentNo, attributes);
    }
}
