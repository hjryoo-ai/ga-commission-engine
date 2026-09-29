-- =====================================================================
-- V12: 비교설명 판매수수료 등급·순위 (Phase E3, ga-disclosure 계약 engine-disclosure.openapi.yaml 1.1.0)
--   * 등급·순위 정책은 버전 데이터(임계치·라벨·사유 코드·동점 규칙 전부 body JSON) — 코드 상수 0.
--     유효기간은 엔진 규약 그대로(양끝 포함, apply_to 기본 9999-12-31), 상태 DRAFT/ACTIVE/SUPERSEDED,
--     기준일 단건 해석·2건 이상 Ambiguous fail-fast(부록 B-3·B-13).
--   * 상품군 코드 체계·소속은 외부 확정 사실의 데이터(설계서 §11 #13) — 외부 productKey → 요율 원장 키 매핑.
--   * 스냅샷은 불변(UPDATE·DELETE 거부 트리거는 Oracle 전용 V103). response_canonical이 재조회 응답 원문이다.
--   이식성: H2(MODE=Oracle)와 Oracle 19c+ 겸용 문법만(IS JSON·트리거는 V103).
-- =====================================================================

CREATE TABLE DISC_GRADING_POLICY (
    policy_version_id VARCHAR2(40)  NOT NULL,
    apply_from        DATE          NOT NULL,
    apply_to          DATE          DEFAULT DATE '9999-12-31' NOT NULL,
    status            VARCHAR2(12)  NOT NULL,
    body              CLOB          NOT NULL,             -- measureKey·population·period·ratioScale·grades·unavailableReasons
    created_by        VARCHAR2(64)  NOT NULL,
    created_at        TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    approved_by       VARCHAR2(64),
    approved_at       TIMESTAMP,
    CONSTRAINT pk_disc_grading_policy PRIMARY KEY (policy_version_id),
    CONSTRAINT ck_disc_grading_status CHECK (status IN ('DRAFT', 'ACTIVE', 'SUPERSEDED')),
    CONSTRAINT ck_disc_grading_period CHECK (apply_to >= apply_from)
);
CREATE INDEX ix_disc_grading_lookup ON DISC_GRADING_POLICY (status, apply_from);

CREATE TABLE DISC_RANKING_POLICY (
    policy_version_id VARCHAR2(40)  NOT NULL,
    apply_from        DATE          NOT NULL,
    apply_to          DATE          DEFAULT DATE '9999-12-31' NOT NULL,
    status            VARCHAR2(12)  NOT NULL,
    body              CLOB          NOT NULL,             -- tieBreak·secondaryKeys
    created_by        VARCHAR2(64)  NOT NULL,
    created_at        TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    approved_by       VARCHAR2(64),
    approved_at       TIMESTAMP,
    CONSTRAINT pk_disc_ranking_policy PRIMARY KEY (policy_version_id),
    CONSTRAINT ck_disc_ranking_status CHECK (status IN ('DRAFT', 'ACTIVE', 'SUPERSEDED')),
    CONSTRAINT ck_disc_ranking_period CHECK (apply_to >= apply_from)
);
CREATE INDEX ix_disc_ranking_lookup ON DISC_RANKING_POLICY (status, apply_from);

-- 유사상품군(외부 코드 체계의 캐시). 코드값은 데이터로만 들어온다.
CREATE TABLE DISC_PRODUCT_GROUP (
    group_code_system VARCHAR2(20)  NOT NULL,
    group_code        VARCHAR2(64)  NOT NULL,
    group_name        VARCHAR2(200) NOT NULL,
    apply_from        DATE          NOT NULL,
    apply_to          DATE          DEFAULT DATE '9999-12-31' NOT NULL,
    CONSTRAINT pk_disc_product_group PRIMARY KEY (group_code_system, group_code),
    CONSTRAINT ck_disc_group_period CHECK (apply_to >= apply_from)
);

-- 소속: 계약 productKey(최대 129자) → COMM_RATE 키(insurer_cd 10자, product_key 40자). 쪼개서 추측하지 않는다.
CREATE TABLE DISC_PRODUCT_GROUP_MEMBER (
    group_code_system VARCHAR2(20)  NOT NULL,
    group_code        VARCHAR2(64)  NOT NULL,
    ext_product_key   VARCHAR2(129) NOT NULL,
    insurer_cd        VARCHAR2(10)  NOT NULL,
    product_key       VARCHAR2(40)  NOT NULL,
    apply_from        DATE          NOT NULL,
    apply_to          DATE          DEFAULT DATE '9999-12-31' NOT NULL,
    CONSTRAINT pk_disc_group_member PRIMARY KEY (group_code_system, group_code, ext_product_key, apply_from),
    CONSTRAINT fk_disc_group_member FOREIGN KEY (group_code_system, group_code)
        REFERENCES DISC_PRODUCT_GROUP (group_code_system, group_code),
    CONSTRAINT ck_disc_member_period CHECK (apply_to >= apply_from)
);

-- 발급 스냅샷(불변). response_canonical = RFC 8785 JCS 응답 원문, response_sha256 = 그 UTF-8 바이트의 SHA-256.
CREATE TABLE DISC_GRADE_SNAPSHOT (
    snapshot_id               VARCHAR2(20)  NOT NULL,        -- GRD-yyyyMMdd-NNNNNN
    tenant_id                 VARCHAR2(32)  NOT NULL,
    as_of_date                DATE          NOT NULL,
    product_group_code        VARCHAR2(64)  NOT NULL,
    grading_policy_version_id VARCHAR2(40)  NOT NULL,
    ranking_policy_version_id VARCHAR2(40)  NOT NULL,
    tie_break                 VARCHAR2(12)  NOT NULL,
    basis_json                CLOB          NOT NULL,
    generated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    response_canonical        CLOB          NOT NULL,
    response_sha256           CHAR(64)      NOT NULL,
    CONSTRAINT pk_disc_grade_snapshot PRIMARY KEY (snapshot_id),
    CONSTRAINT ck_disc_snapshot_tie CHECK (tie_break IN ('SHARED_RANK', 'STRICT'))
);

-- 항목: OK는 6개 필드 전부 NOT NULL·reason NULL, UNAVAILABLE은 그 반대(계약 oneOf의 DB 표현).
-- ratio_to_avg는 생성 시 한 번 만든 문자열 그대로(VARCHAR2) — NUMBER로 두지 않는다(재포맷 경로 차단).
CREATE TABLE DISC_GRADE_SNAPSHOT_ITEM (
    snapshot_id   VARCHAR2(20)  NOT NULL,
    product_key   VARCHAR2(129) NOT NULL,
    item_order    NUMBER(4)     NOT NULL,
    status        VARCHAR2(12)  NOT NULL,
    ratio_to_avg  VARCHAR2(32),
    grade         VARCHAR2(32),
    grade_label   VARCHAR2(64),
    grade_ordinal NUMBER(3),
    rank_in_set   NUMBER(4),
    tie           NUMBER(1),
    reason        VARCHAR2(40),
    CONSTRAINT pk_disc_snapshot_item PRIMARY KEY (snapshot_id, product_key),
    CONSTRAINT uq_disc_snapshot_item_order UNIQUE (snapshot_id, item_order),
    CONSTRAINT fk_disc_snapshot_item FOREIGN KEY (snapshot_id) REFERENCES DISC_GRADE_SNAPSHOT (snapshot_id),
    CONSTRAINT ck_disc_item_status CHECK (status IN ('OK', 'UNAVAILABLE')),
    CONSTRAINT ck_disc_item_tie CHECK (tie IN (0, 1)),
    CONSTRAINT ck_disc_item_shape CHECK (
        (status = 'OK' AND ratio_to_avg IS NOT NULL AND grade IS NOT NULL AND grade_label IS NOT NULL
             AND grade_ordinal IS NOT NULL AND rank_in_set IS NOT NULL AND tie IS NOT NULL AND reason IS NULL)
        OR (status = 'UNAVAILABLE' AND ratio_to_avg IS NULL AND grade IS NULL AND grade_label IS NULL
             AND grade_ordinal IS NULL AND rank_in_set IS NULL AND tie IS NULL AND reason IS NOT NULL))
);

-- 일자별 채번(GRD-yyyyMMdd-NNNNNN의 NNNNNN). 같은 날 동시 발급은 행 잠금으로 직렬화.
CREATE TABLE DISC_GRADE_SNAPSHOT_SEQ (
    seq_date DATE      NOT NULL,
    last_no  NUMBER(6) NOT NULL,
    CONSTRAINT pk_disc_snapshot_seq PRIMARY KEY (seq_date),
    CONSTRAINT ck_disc_snapshot_seq CHECK (last_no BETWEEN 1 AND 999999)
);
