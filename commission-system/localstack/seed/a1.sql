-- 로컬 스택 시드(E2 ⑤, 계획 P8) — RuleFixtures.standardRules() + CalcTestHarness.standardDirectory()와 같은 값(허구 데이터).
-- 둘의 일치는 시험이 증명한다: LocalStackSeedIT(룰·조직 조회가 인메모리 픽스처와 같다)와 CI 잡 "로컬 스택(A-1 피드)"(피드 바이트 = 두 번째 판).
-- 하나라도 다르면 실패한다(주입 S11). 요율 ID(1001~)·한도 룰 ID(1)·커브 ID(1, 2)는 이벤트의 ruleVersionIds·ruleVersionId에 실리므로 명시한다.
-- 빈 DB(Flyway 직후)에 한 번만 — LocalStackScenario가 아웃박스가 비어 있지 않으면 거부한다. 문장 구분은 줄 끝의 세미콜론.

-- 수수료 유형 속성 (한도 포함 / 환수 대상) — 2026-01-01부터, 원 단위 절사
INSERT INTO COMM_TYPE_MST (comm_type, apply_from, limit_included, rounding_policy, clawback_target) VALUES ('FY_COMM', DATE '2026-01-01', 'Y', 'KRW_FLOOR', 'Y');
INSERT INTO COMM_TYPE_MST (comm_type, apply_from, limit_included, rounding_policy, clawback_target) VALUES ('RENEWAL', DATE '2026-01-01', 'N', 'KRW_FLOOR', 'N');
INSERT INTO COMM_TYPE_MST (comm_type, apply_from, limit_included, rounding_policy, clawback_target) VALUES ('INCENTIVE', DATE '2026-01-01', 'Y', 'KRW_FLOOR', 'Y');
INSERT INTO COMM_TYPE_MST (comm_type, apply_from, limit_included, rounding_policy, clawback_target) VALUES ('SETTLEMENT_SUPPORT', DATE '2026-01-01', 'Y', 'KRW_FLOOR', 'Y');
INSERT INTO COMM_TYPE_MST (comm_type, apply_from, limit_included, rounding_policy, clawback_target) VALUES ('OVERRIDE', DATE '2026-01-01', 'N', 'KRW_FLOOR', 'N');
INSERT INTO COMM_TYPE_MST (comm_type, apply_from, limit_included, rounding_policy, clawback_target) VALUES ('DEFERRED', DATE '2026-01-01', 'N', 'KRW_FLOOR', 'N');
INSERT INTO COMM_TYPE_MST (comm_type, apply_from, limit_included, rounding_policy, clawback_target) VALUES ('CLAWBACK', DATE '2026-01-01', 'N', 'KRW_FLOOR', 'N');

-- OUTBOUND 초년도 모집수수료: 신계약 성립(회차 NULL) 700%, 2~12회차 각 15%
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1001, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'FY_COMM', NULL, 7.0, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1002, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'FY_COMM', 2, 0.15, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1003, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'FY_COMM', 3, 0.15, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1004, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'FY_COMM', 4, 0.15, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1005, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'FY_COMM', 5, 0.15, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1006, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'FY_COMM', 6, 0.15, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1007, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'FY_COMM', 7, 0.15, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1008, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'FY_COMM', 8, 0.15, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1009, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'FY_COMM', 9, 0.15, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1010, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'FY_COMM', 10, 0.15, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1011, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'FY_COMM', 11, 0.15, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1012, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'FY_COMM', 12, 0.15, DATE '2026-01-01', 1, 'ACTIVE');
-- 계속수수료(2차년도~): 13~24회차 각 2%
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1013, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'RENEWAL', 13, 0.02, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1014, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'RENEWAL', 14, 0.02, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1015, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'RENEWAL', 15, 0.02, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1016, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'RENEWAL', 16, 0.02, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1017, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'RENEWAL', 17, 0.02, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1018, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'RENEWAL', 18, 0.02, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1019, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'RENEWAL', 19, 0.02, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1020, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'RENEWAL', 20, 0.02, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1021, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'RENEWAL', 21, 0.02, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1022, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'RENEWAL', 22, 0.02, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1023, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'RENEWAL', 23, 0.02, DATE '2026-01-01', 1, 'ACTIVE');
INSERT INTO COMM_RATE (rate_id, direction, insurer_cd, product_key, comm_type, installment_no, rate, apply_from, version_no, status) VALUES (1024, 'OUTBOUND', 'SAMLIFE', 'WHOLE-LIFE-20Y', 'RENEWAL', 24, 0.02, DATE '2026-01-01', 1, 'ACTIVE');

-- 등급별 지급률
INSERT INTO AGENT_PAYOUT_RATE (grade_cd, comm_type, payout_rate, apply_from) VALUES ('SR', 'FY_COMM', 0.9, DATE '2026-01-01');
INSERT INTO AGENT_PAYOUT_RATE (grade_cd, comm_type, payout_rate, apply_from) VALUES ('SR', 'RENEWAL', 0.9, DATE '2026-01-01');
INSERT INTO AGENT_PAYOUT_RATE (grade_cd, comm_type, payout_rate, apply_from) VALUES ('SR', 'INCENTIVE', 1.0, DATE '2026-01-01');
INSERT INTO AGENT_PAYOUT_RATE (grade_cd, comm_type, payout_rate, apply_from) VALUES ('JR', 'FY_COMM', 0.7, DATE '2026-01-01');
INSERT INTO AGENT_PAYOUT_RATE (grade_cd, comm_type, payout_rate, apply_from) VALUES ('JR', 'RENEWAL', 0.7, DATE '2026-01-01');
INSERT INTO AGENT_PAYOUT_RATE (grade_cd, comm_type, payout_rate, apply_from) VALUES ('JR', 'INCENTIVE', 1.0, DATE '2026-01-01');

-- 1200%룰: GA→설계사 2026-07-01 체결분부터, 초년도 12개월, 초과분 이연, 환수 시 복원
INSERT INTO LIMIT_RULE (rule_id, channel_type, limit_multiple, fy_window_months, over_limit_action, clawback_restores, apply_from) VALUES (1, 'GA_TO_AGENT', 12.00, 12, 'DEFER_AFTER_FY', 'Y', DATE '2026-07-01');

-- 분급 커브: 2027 체결분 4년(2028-12-31까지), 2029 체결분 7년
INSERT INTO DEFERRAL_CURVE (curve_id, curve_name, apply_from, apply_to) VALUES (1, '4년 분급(2027)', DATE '2027-01-01', DATE '2028-12-31');
INSERT INTO DEFERRAL_CURVE_DTL (curve_id, month_no, pct) VALUES (1, 0, 0.4);
INSERT INTO DEFERRAL_CURVE_DTL (curve_id, month_no, pct) VALUES (1, 12, 0.2);
INSERT INTO DEFERRAL_CURVE_DTL (curve_id, month_no, pct) VALUES (1, 24, 0.2);
INSERT INTO DEFERRAL_CURVE_DTL (curve_id, month_no, pct) VALUES (1, 36, 0.2);
INSERT INTO DEFERRAL_CURVE (curve_id, curve_name, apply_from) VALUES (2, '7년 분급(2029)', DATE '2029-01-01');
INSERT INTO DEFERRAL_CURVE_DTL (curve_id, month_no, pct) VALUES (2, 0, 0.3);
INSERT INTO DEFERRAL_CURVE_DTL (curve_id, month_no, pct) VALUES (2, 12, 0.1);
INSERT INTO DEFERRAL_CURVE_DTL (curve_id, month_no, pct) VALUES (2, 24, 0.1);
INSERT INTO DEFERRAL_CURVE_DTL (curve_id, month_no, pct) VALUES (2, 36, 0.1);
INSERT INTO DEFERRAL_CURVE_DTL (curve_id, month_no, pct) VALUES (2, 48, 0.1);
INSERT INTO DEFERRAL_CURVE_DTL (curve_id, month_no, pct) VALUES (2, 60, 0.1);
INSERT INTO DEFERRAL_CURVE_DTL (curve_id, month_no, pct) VALUES (2, 72, 0.1);
INSERT INTO DEFERRAL_CURVE_DTL (curve_id, month_no, pct) VALUES (2, 84, 0.1);

-- 환수율(전 상품 공통): 해약·실효 0~6회차 70%, 7~12회차 40% / 청약철회 전액
INSERT INTO CLAWBACK_RULE (product_key, event_type, from_installment, to_installment, clawback_pct, apply_from) VALUES (NULL, 'CANCEL', 0, 6, 0.7, DATE '2026-01-01');
INSERT INTO CLAWBACK_RULE (product_key, event_type, from_installment, to_installment, clawback_pct, apply_from) VALUES (NULL, 'CANCEL', 7, 12, 0.4, DATE '2026-01-01');
INSERT INTO CLAWBACK_RULE (product_key, event_type, from_installment, to_installment, clawback_pct, apply_from) VALUES (NULL, 'LAPSE', 0, 6, 0.7, DATE '2026-01-01');
INSERT INTO CLAWBACK_RULE (product_key, event_type, from_installment, to_installment, clawback_pct, apply_from) VALUES (NULL, 'LAPSE', 7, 12, 0.4, DATE '2026-01-01');
INSERT INTO CLAWBACK_RULE (product_key, event_type, from_installment, to_installment, clawback_pct, apply_from) VALUES (NULL, 'WITHDRAW', 0, 999, 1.0, DATE '2026-01-01');

-- 조직 오버라이드: FY_COMM에 대해 팀 5%, 지점 3%, 본부 2%
INSERT INTO ORG_OVERRIDE_RATE (org_level, comm_type, override_rate, apply_from) VALUES ('TEAM', 'FY_COMM', 0.05, DATE '2026-01-01');
INSERT INTO ORG_OVERRIDE_RATE (org_level, comm_type, override_rate, apply_from) VALUES ('BRANCH', 'FY_COMM', 0.03, DATE '2026-01-01');
INSERT INTO ORG_OVERRIDE_RATE (org_level, comm_type, override_rate, apply_from) VALUES ('HQ', 'FY_COMM', 0.02, DATE '2026-01-01');

-- 조직·설계사(허구): A-1001 SR 등급, 팀 T1 → 지점 B1 → 본부 H1 — 2025-01-01부터
INSERT INTO ORG_MST (org_id, org_name, org_level) VALUES ('H1', '본부1', 'HQ');
INSERT INTO ORG_MST (org_id, org_name, org_level, parent_org) VALUES ('B1', '지점1', 'BRANCH', 'H1');
INSERT INTO ORG_MST (org_id, org_name, org_level, parent_org) VALUES ('T1', '팀1', 'TEAM', 'B1');
INSERT INTO AGENT_MST (agent_id, agent_name, appointed_on) VALUES ('A-1001', '허구설계사', DATE '2025-01-01');
INSERT INTO AGENT_GRADE_HIST (agent_id, grade_cd, apply_from) VALUES ('A-1001', 'SR', DATE '2025-01-01');
INSERT INTO AGENT_ORG_HIST (agent_id, org_id, apply_from) VALUES ('A-1001', 'T1', DATE '2025-01-01');
INSERT INTO AGENT_ORG_HIST (agent_id, org_id, apply_from) VALUES ('A-1001', 'B1', DATE '2025-01-01');
INSERT INTO AGENT_ORG_HIST (agent_id, org_id, apply_from) VALUES ('A-1001', 'H1', DATE '2025-01-01');
