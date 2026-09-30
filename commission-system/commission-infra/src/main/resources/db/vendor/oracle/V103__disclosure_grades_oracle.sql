-- =====================================================================
-- V103: Phase E3 — 비교설명 등급·순위의 Oracle 전용 보강(H2 미지원 요소)
--   1) JSON 무결성(IS JSON)
--   2) 스냅샷 불변: UPDATE·DELETE 거부 트리거(엔진 최초의 DB 강제 불변 — COMM_CALC의 "UPDATE 금지"는 규약이었다).
--      문장 수준 트리거라 대상 행이 0건이어도 거부한다. TRUNCATE는 DML이 아니라 발동하지 않는다(운영 권한으로 통제).
--   3) 정책 ACTIVE 중복의 2차 이중화: 같은 apply_from의 ACTIVE를 DDL로 차단(COMM_RATE ux_comm_rate_active와 같은 방식).
--      기간 "겹침" 자체의 차단은 승인 워크플로 몫이고, 해석기는 겹침을 Ambiguous로 fail-fast한다.
-- =====================================================================

ALTER TABLE DISC_GRADING_POLICY ADD CONSTRAINT ck_disc_grading_body_json CHECK (body IS JSON);
ALTER TABLE DISC_RANKING_POLICY ADD CONSTRAINT ck_disc_ranking_body_json CHECK (body IS JSON);
ALTER TABLE DISC_GRADE_SNAPSHOT ADD CONSTRAINT ck_disc_snapshot_basis_json CHECK (basis_json IS JSON);
ALTER TABLE DISC_GRADE_SNAPSHOT ADD CONSTRAINT ck_disc_snapshot_resp_json CHECK (response_canonical IS JSON);

CREATE OR REPLACE TRIGGER trg_disc_snapshot_immutable
    BEFORE UPDATE OR DELETE ON DISC_GRADE_SNAPSHOT
BEGIN
    RAISE_APPLICATION_ERROR(-20301, 'DISC_GRADE_SNAPSHOT is immutable (Phase E3): no UPDATE or DELETE');
END;
/

CREATE OR REPLACE TRIGGER trg_disc_snapshot_item_immutable
    BEFORE UPDATE OR DELETE ON DISC_GRADE_SNAPSHOT_ITEM
BEGIN
    RAISE_APPLICATION_ERROR(-20302, 'DISC_GRADE_SNAPSHOT_ITEM is immutable (Phase E3): no UPDATE or DELETE');
END;
/

CREATE UNIQUE INDEX ux_disc_grading_active ON DISC_GRADING_POLICY (
    CASE WHEN status = 'ACTIVE' THEN TO_CHAR(apply_from, 'YYYYMMDD') END
);
CREATE UNIQUE INDEX ux_disc_ranking_active ON DISC_RANKING_POLICY (
    CASE WHEN status = 'ACTIVE' THEN TO_CHAR(apply_from, 'YYYYMMDD') END
);
