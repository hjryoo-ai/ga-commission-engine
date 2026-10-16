-- =====================================================================
-- V106: Phase E1 — 아웃박스의 Oracle 전용 보강(H2 미지원 요소, E1 D-2·D-17)
--   1) payload JSON 무결성(IS JSON)
--   2) append-only: DELETE는 문장 수준 트리거가 거부(0행이어도). UPDATE는 행 수준 트리거가 seq를 NULL → 값으로 한 번 붙이는 것만
--      허용하고, 그 밖의 컬럼을 SET에 넣는 UPDATE는 거부한다. OUTBOX_SEQ는 지울 수 없고 last_seq는 줄지 않는다.
--   3) 미부여 행 탐색 인덱스(seq IS NULL인 행만 담는 함수 기반 인덱스)
--   번호: 전역 단조 — V105 다음.
-- =====================================================================

ALTER TABLE OUTBOX_EVENT ADD CONSTRAINT ck_outbox_payload_json CHECK (payload IS JSON);

CREATE OR REPLACE TRIGGER trg_outbox_event_no_delete
    BEFORE DELETE ON OUTBOX_EVENT
BEGIN
    RAISE_APPLICATION_ERROR(-20311, 'OUTBOX_EVENT is append-only (E1): no DELETE');
END;
/

-- UPDATE: 행 수준. 순번 말고 다른 컬럼이 SET에 있으면 거부(UPDATE OF 절은 CLOB을 받지 않으므로 UPDATING('컬럼') 술어로), 순번은 NULL에서
-- 값으로 한 번만. 0행 UPDATE는 바뀌는 행이 없으므로 발동할 일도 없다.
CREATE OR REPLACE TRIGGER trg_outbox_event_append_only
    BEFORE UPDATE ON OUTBOX_EVENT
    FOR EACH ROW
BEGIN
    IF UPDATING('EVENT_NO') OR UPDATING('EVENT_ID') OR UPDATING('EVENT_TYPE') OR UPDATING('SCHEMA_VER')
       OR UPDATING('AGGREGATE_KIND') OR UPDATING('AGGREGATE_ID') OR UPDATING('OCCURRED_AT')
       OR UPDATING('PAYLOAD') OR UPDATING('CREATED_AT') THEN
        RAISE_APPLICATION_ERROR(-20312, 'OUTBOX_EVENT is append-only (E1): only the one-time seq assignment is allowed');
    END IF;
    IF :OLD.seq IS NOT NULL OR :NEW.seq IS NULL THEN
        RAISE_APPLICATION_ERROR(-20313, 'OUTBOX_EVENT.seq is assigned once (E1): event_no=' || :OLD.event_no);
    END IF;
END;
/

CREATE OR REPLACE TRIGGER trg_outbox_seq_no_delete
    BEFORE DELETE ON OUTBOX_SEQ
BEGIN
    RAISE_APPLICATION_ERROR(-20314, 'OUTBOX_SEQ cannot be deleted (E1)');
END;
/

CREATE OR REPLACE TRIGGER trg_outbox_seq_forward
    BEFORE UPDATE ON OUTBOX_SEQ
    FOR EACH ROW
BEGIN
    IF :NEW.last_seq < :OLD.last_seq OR :NEW.id <> :OLD.id THEN
        RAISE_APPLICATION_ERROR(-20315, 'OUTBOX_SEQ.last_seq only moves forward (E1)');
    END IF;
END;
/

CREATE INDEX ix_outbox_pending ON OUTBOX_EVENT (CASE WHEN seq IS NULL THEN event_no END);
