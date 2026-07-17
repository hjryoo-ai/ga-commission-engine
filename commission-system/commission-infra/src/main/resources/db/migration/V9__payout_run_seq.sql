-- =====================================================================
-- V9: Phase 11 — 지급 런 회차 (설계서 §7 v1.1, §11.12)
--   상계·원천세·정산행 생성이 마감에서 지급 런으로 이동함에 따라
--   AGENT_SETTLEMENT는 (마감월 × 런 회차)에 귀속된다. 월 N회 지급 주기 대응.
-- =====================================================================

ALTER TABLE AGENT_SETTLEMENT ADD run_seq NUMBER DEFAULT 1 NOT NULL;

CREATE INDEX ix_agent_settlement_run ON AGENT_SETTLEMENT (close_ym, run_seq);
