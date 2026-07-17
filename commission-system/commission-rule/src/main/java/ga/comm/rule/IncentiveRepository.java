package ga.comm.rule;

import ga.comm.rule.model.IncentiveRule;

import java.time.LocalDate;
import java.util.List;

/**
 * 시책 조회 포트 (Phase 13). 룰 조회 규약(기준일 필수, 부록 B-3)을 따른다.
 *
 * <p>요율과 달리 시책은 <b>중첩 적용</b>되므로 단건 해석이 아니라 기준일에 유효한 ACTIVE 시책
 * <b>전부</b>를 돌려준다. 대상 필터(보험사/상품/채널) 매칭과 조건식 판정은 호출측(계산 Step)의 몫이다.
 * 같은 시책 코드에는 겹치는 ACTIVE가 없다(승인 워크플로가 보장) — 즉 코드별로는 최대 1건이 유효하다.
 */
public interface IncentiveRepository {

    /** 기준일(업무 발생일)에 유효한 ACTIVE 시책 전체. */
    List<IncentiveRule> findActiveAt(LocalDate baseDate);
}
