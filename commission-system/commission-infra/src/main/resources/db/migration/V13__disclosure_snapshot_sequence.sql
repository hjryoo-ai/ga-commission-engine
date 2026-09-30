-- =====================================================================
-- V13: 비교설명 스냅샷 채번을 Oracle SEQUENCE 하나로 (E3.1, E3 수용 심사 §3-4)
--   * 일자별 카운터 행(DISC_GRADE_SNAPSHOT_SEQ, V12)은 같은 날 첫 발급이 동시에 오면 PK 위반으로 한쪽이 실패했다
--     (E3 보고서 §5-15). SEQUENCE는 행 잠금·MERGE·재시도가 없다.
--   * snapshot_id = GRD-{yyyyMMdd}-{NEXTVAL}. 뒤 숫자는 날마다 다시 세지 않는다(계약은 형식만 정하고 일자별 재시작을
--     요구하지 않는다). 번호에 빈틈이 생길 수 있다(롤백·캐시) — 번호는 식별자일 뿐 건수가 아니다.
--   * START WITH 1000000: V12 일자 카운터가 쓴 6자리 대역(000001~999999)과 겹치지 않는 7자리 전용 대역
--     (INCENTIVE_ADMIN_SEQ와 같은 방식, 설계서 v1.1.3 ②). 기존 스냅샷 ID와 충돌할 수 없고 ID 길이는 20자로 고정
--     (snapshot_id VARCHAR2(20)). MAXVALUE 9999999 NOCYCLE — 소진 시 조용히 돌지 않고 실패한다(부록 B-13).
--   이식성: H2(MODE=Oracle)와 Oracle 19c+ 겸용.
-- =====================================================================

CREATE SEQUENCE DISC_GRADE_SNAPSHOT_NO START WITH 1000000 INCREMENT BY 1 MINVALUE 1000000 MAXVALUE 9999999 NOCYCLE CACHE 20;

DROP TABLE DISC_GRADE_SNAPSHOT_SEQ;
