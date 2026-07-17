-- =====================================================================
-- V100: Oracle 전용 보강 (설계서 §5 비고, §10 Phase 10 완료 기준)
--   H2(MODE=Oracle)가 지원하지 않는 요소만 여기에 둔다.
--   Flyway locations: 공통(db/migration) + Oracle(db/vendor/oracle)
-- =====================================================================

-- 1) JSON 무결성 체크 (§5.4, §5.5)
ALTER TABLE POLICY_EVENT ADD CONSTRAINT ck_event_payload_json CHECK (payload IS JSON);
ALTER TABLE COMM_CALC ADD CONSTRAINT ck_calc_rules_json CHECK (rule_versions IS JSON);
ALTER TABLE COMM_CALC ADD CONSTRAINT ck_calc_trace_json CHECK (calc_trace IS JSON);
ALTER TABLE INBOUND_STATEMENT ADD CONSTRAINT ck_inbound_raw_json CHECK (raw_fields IS JSON);

-- 2) COMM_RATE "겹치는 ACTIVE 없음" 불변식의 2차 이중화 (§6.6)
--    function-based unique index — ACTIVE 행만 인덱싱(비ACTIVE는 전 컬럼 NULL이라 제외).
--    같은 키 + 같은 apply_from의 ACTIVE 중복을 DDL로 차단한다.
--    (기간 "겹침" 자체의 차단은 1차 강제인 승인 워크플로의 몫 — 트리밍/SUPERSEDE만 허용)
CREATE UNIQUE INDEX ux_comm_rate_active ON COMM_RATE (
    CASE WHEN status = 'ACTIVE' THEN direction END,
    CASE WHEN status = 'ACTIVE' THEN insurer_cd END,
    CASE WHEN status = 'ACTIVE' THEN product_key END,
    CASE WHEN status = 'ACTIVE' THEN comm_type END,
    CASE WHEN status = 'ACTIVE' THEN NVL(TO_CHAR(installment_no), '-') END,
    CASE WHEN status = 'ACTIVE' THEN TO_CHAR(apply_from, 'YYYYMMDD') END
);

-- 3) COMM_CALC 마감월 파티셔닝 (§5.5)
--    설계서의 "INTERVAL" 파티셔닝은 close_ym이 VARCHAR2(6)라서 불가능하다
--    (Oracle 인터벌 파티셔닝은 NUMBER/DATE 키만 허용). RANGE + MAXVALUE로 대체하고,
--    연 단위 파티션 추가는 운영 DBA 절차(SPLIT PARTITION)로 관리한다.
ALTER TABLE COMM_CALC MODIFY
    PARTITION BY RANGE (close_ym) (
        PARTITION p2026 VALUES LESS THAN ('202701'),
        PARTITION p2027 VALUES LESS THAN ('202801'),
        PARTITION p2028 VALUES LESS THAN ('202901'),
        PARTITION pmax  VALUES LESS THAN (MAXVALUE)
    ) UPDATE INDEXES;
