package ga.comm.calc.store;

import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.time.CloseYm;

/**
 * 귀속 마감월 결정. 기본은 이벤트 발생월.
 * 마감 모듈은 "이미 CLOSED된 월이면 다음 OPEN 월로 귀속"하는 구현으로 대체한다 (설계서 §6.4).
 */
@FunctionalInterface
public interface CloseYmResolver {

    CloseYm resolveFor(PolicyEvent event);

    static CloseYmResolver byEventDate() {
        return event -> CloseYm.from(event.eventDate());
    }
}
