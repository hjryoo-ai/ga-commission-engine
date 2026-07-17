-- =====================================================================
-- V102: 시책 마스터 ACTIVE function-based unique index (설계서 §6.6, Phase 14 선행 소과제)
--   요율의 ux_comm_rate_active(V100)와 동형 — "겹치는 ACTIVE 없음" 불변식의 최종 심판.
--   앱 계층 겹침 검사(IncentiveApprovalService)를 동시 승인 2건이 모두 통과해도, 같은 키 +
--   같은 apply_from의 ACTIVE 중복은 이 인덱스가 DB 레벨에서 차단한다(→ 경합 승인 거부).
--   (재시도 러너 + 결정적 인터리빙 IT는 요율 §6.6과 동형으로 후속 Phase — 이번 범위는 인덱스만.)
--
--   요율과의 결정적 차이: 시책의 대상 필터(insurer_cd/product_key/channel)는 NULL=전체(와일드카드)로
--   nullable이다. Oracle은 유일성 비교에서 NULL을 서로 다른 값으로 취급하므로, NVL로 감싸지 않으면
--   "insurer_cd IS NULL"인 두 전체-대상 ACTIVE가 충돌하지 않고 새어나간다. 따라서 각 필터를 NVL('*')로
--   고정한다(요율은 이 컬럼들이 NOT NULL이라 불필요했다).
-- =====================================================================
CREATE UNIQUE INDEX ux_incentive_active ON INCENTIVE_MST (
    CASE WHEN status = 'ACTIVE' THEN incentive_cd END,
    CASE WHEN status = 'ACTIVE' THEN NVL(insurer_cd, '*') END,
    CASE WHEN status = 'ACTIVE' THEN NVL(product_key, '*') END,
    CASE WHEN status = 'ACTIVE' THEN NVL(channel, '*') END,
    CASE WHEN status = 'ACTIVE' THEN TO_CHAR(apply_from, 'YYYYMMDD') END
);
