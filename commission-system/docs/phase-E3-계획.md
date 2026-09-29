# Phase E3 구현 계획 (승인 대기)

> 지시문: `docs/phase-E3-지시문.md` v1.0. 저장소: git 루트 `ai-comm/`(origin `hjryoo-ai/ga-commission-engine`), Gradle 빌드 `commission-system/`. 브랜치 `work/phase-e3`, 태그 `phase-e3`.
> 이 계획은 저장소를 읽기만 하고 작성했다. **엔진 빌드·테스트는 아직 한 번도 실행하지 않았다**(아래 Q1 때문).

## 0. 지시문 전제와 실제 저장소의 차이 (먼저 결정이 필요한 것)

| # | 지시문 전제 | 실제 | 제안 |
|---|---|---|---|
| **Q1** | "jqwik 금지, 이 저장소의 속성 테스트 방식 유지" | 루트 `build.gradle.kts:30`이 **모든 모듈에 `net.jqwik:jqwik:1.10.1`**을 넣는다. 속성 테스트 3개가 jqwik이다(`MoneyPropertyTest`, `LimitLedgerPropertyTest`, `DeferralSplitPropertyTest`). 두 요구가 서로 모순이다. 또 jqwik 1.10은 AI 에이전트 사용 배제 조항과 출력 삽입 지시문을 가진 버전이라, E12(기존 전 테스트 무손상)를 증명하려고 빌드를 돌리는 것 자체가 그 도구를 에이전트가 실행하는 일이 된다. | **선행 소과제 E3-0**: 3개 테스트를 JUnit 5 `@ParameterizedTest` + 시드 고정 생성기(`ga-disclosure`의 `SeededCases`를 Java 21로 옮긴 테스트 픽스처)로 바꾼다. 같은 속성, 같거나 더 많은 시행 수(`tries=100` 이상)로 하고, 속성별 대조표를 보고서에 싣는다. 그다음 jqwik 의존을 제거하고 `net.jqwik` 해석을 빌드에서 차단한다. **승인 전에는 엔진 빌드를 실행하지 않는다.** |
| **Q2** | "`/internal/**`는 서비스 토큰(기존 규약)", "이 인스턴스의 테넌트" | `/internal/**` 규칙도, 서비스 토큰 필터도 없다(HTTP Basic + 메모리 사용자뿐). 테넌트 개념도 설정·코드·DB 어디에도 없다. | 새로 만든다. `/internal/**` 전용 `SecurityFilterChain`(기존 체인보다 앞, 무상태): Bearer 토큰을 설정값(`app.internal.service-token-sha256`, 환경변수, 토큰의 SHA-256만 보관)과 상수 시간 비교하고, 실패하면 401. `app.tenant-id`는 필수 설정이며 없으면 기동 실패(B-13). 불일치하면 403. |
| **Q3** | 계약에 403·명시 오류 | 계약에는 400·409(POST), 404(GET)뿐이다. 401·403 응답이 없고, "정책 0건"과 "자기 검증 실패"(500 금지)에 해당하는 상태도 없다. | **`ga-disclosure` 계약 변경 요청**: 두 연산에 401·403, POST에 422(`NO_POLICY`, `POLICY_SELF_CHECK_FAILED`)를 추가한다. 지시문대로 `ga-disclosure`에서 먼저 작은 PR로 고치고, 엔진은 그 커밋을 고정 참조한다. |
| **Q4** | 상품 키 = 요율 원장 키 | 계약 `productKey`는 `INSURER:PRODUCT` 형식으로 최대 129자다. 엔진 `ProductKey`는 40자, `InsurerCode`는 10자다. 상품군(상품군 코드·소속) 개념은 엔진에 **없다**. | 새 데이터 테이블 `DISC_PRODUCT_GROUP`(코드 체계·코드·유효기간)과 `DISC_PRODUCT_GROUP_MEMBER`(외부 `productKey` → 엔진 `(insurer_cd, product_key)` 매핑, 유효기간)를 둔다. 매핑이 없는 키는 `NOT_IN_GROUP`으로 처리하고, 문자열을 쪼개서 추측하지 않는다. 코드 체계가 없으면 400. |
| **Q5** | "범용 룰 버전 테이블이 있으면 그것을" | 범용 테이블은 없다. 테이블마다 `apply_from`/`apply_to`(포함, 기본 `9999-12-31`)와 `status`(DRAFT/ACTIVE/SUPERSEDED)를 갖고, `OracleRuleRepository.unique()`가 2건 이상이면 `AmbiguousRuleException`을 던진다. | 지시문대로 `DISC_GRADING_POLICY`·`DISC_RANKING_POLICY`를 새로 만든다. 엔진 규약(닫힌 구간, `9999-12-31`, 상태 어휘, `unique()`/`AmbiguousRuleException`, 0건은 `RuleNotFoundException`)을 그대로 따른다. |
| **Q6** | `GradingPolicy` 재정의, 3분위 제거(`ga-disclosure` 설계서 §4.1) vs E12 기존 테스트 무손상 | `GradingPolicy { String grade(int rank, int total, Money value) }`는 **순위 기반**이다. `TercileGradingPolicy`(`commission-api/.../disclosure/TercileGradingPolicy.java:9-19`)가 `Math.ceil(total / 3.0)`으로 **double**을 쓴다(B-1 위반). `RankingService.rank(DisclosureAggregate, CommTypeCode)`는 실현 순액 **내림차순**(1위 = 가장 많음), 동점은 입력 순서다. 이것은 비교**공시** 집계 경로다(Phase 15, 컨트롤러 없음, `DisclosureServiceTest`가 고정). | 비교**설명** 경로를 새로 만든다(`ga.comm.api.disclosure.grade`). 등급 정책 레코드와 세트 순위는 `RankingService.rankInSet(...)` 신설 메서드로 넣는다. 기존 `rank`·`GradingPolicy`는 건드리지 않는다(E12). `TercileGradingPolicy`의 double만 동치 정수식 `(total + 2) / 3`으로 바꾸고, 기존 테스트로 동치를 증명한다. 3분위 제거는 비교공시 서식 확정(§11 #13) 때 별도 Phase로 미룬다. |
| **Q7** | 설계서 "v1.2.1로" | v1.2.1은 이미 있다(2026-07-17, Phase 16 마무리). H1 제목은 아직 "v1.1"이다. | **v1.2.2**로 하고 H1 제목도 현행 버전으로 고친다. |
| **Q8** | (병합 방식 미언급) | `CONTRIBUTING.md`는 squash 병합을 규정한다. | 보고서가 커밋 해시를 인용하므로 `ga-disclosure`와 같이 **merge commit**을 쓰고 CONTRIBUTING을 고친다. |
| **Q9** | canonical JSON | 엔진에는 SHA-256도 canonical JSON도 없다. `ga-disclosure`의 `platform-canonical`은 Java 25 바이트코드라 Java 21 엔진이 소비할 수 없다. | 같은 라이브러리 `io.github.erdtman:java-json-canonicalization:1.1`(RFC 8785)을 직접 쓴다. 의존성 검증 메타데이터(`verification-metadata.xml`)도 갱신한다. |

## 1. 현재 코드 (계획 ①)

- `commission-api/src/main/java/ga/comm/api/disclosure/`:
  - `GradingPolicy.java:14-17`
  - `TercileGradingPolicy.java:9-19`: `third = max(1, ceil(total/3.0))`, 이후 `rank ≤ third → A`, `≤ 2·third → B`, 나머지 C.
  - `RankingService.java`: 생성자 `()`/`(GradingPolicy)`, `rank(DisclosureAggregate, CommTypeCode) → List<CommissionRank(insurerCd, productKey, net, rank, grade)>`. 순액 내림차순이고 동점은 입력 순서다.
- 배선: `commission-app/.../config/ServiceConfig.java:134-143`. 테스트는 `DisclosureServiceTest.java:111-131`.

## 2. 측정(measure) 후보와 선택 (계획 ②)

- **데이터 출처**: `COMM_RATE`(V1__masters.sql:22-44)의 `direction='INBOUND'`(보험사→GA, 매출측) 행.
  - 키: `(insurer_cd, product_key, comm_type, installment_no)`.
  - 값: `rate NUMBER(9,6)`.
  - 기간: `apply_from`/`apply_to`(포함).
  - 상태: `status`.
- **v1 측정 함수 `FY_SALES_COMMISSION_RATE` (예시 측정)**:
  - 무엇을 읽나: 상품의 INBOUND 요율 중 `comm_type`·`installment_no`가 정책 데이터(`measureParams: {"commType":"FY_COMM","installmentNo":null}`)와 일치하는 것.
  - 기준일: 측정 기준일(= `period`가 정하는 구간의 말일)에 ACTIVE인 1건. 2건 이상이면 Ambiguous.
  - `TRAILING_QUARTER` + `REQUEST_DATE`: 요청 기준일 직전에 완료된 분기. 예: 기준일 2026-09-23 → 2026Q2, 측정 기준일 2026-06-30. `basis.period = "2026Q2"`.
  - 요율은 있으나 측정 기준일에 유효하지 않음 → `OUTSIDE_PERIOD`. 요율이 아예 없음 → `NO_RATE_DATA`.
- **모집단**: 측정 기준일에 유효한 상품군 소속 중 측정값이 있는 상품.
  - `groupPopulation` = 모집단 크기, `groupAvgSource = ENGINE_LEDGER`.
  - `minPopulation` 미만이면 요청 세트 전체가 `INSUFFICIENT_POPULATION`.
- **비율 계산 (반올림 한 번)**: `ratio = measure × n / Σmeasure`를 `BigDecimal.divide(Σ, ratioScale, ratioRounding)`으로 계산한다.
  - 평균을 따로 반올림하지 않으므로 이중 반올림이 없다.
  - 이 결과를 `toPlainString()`해 **한 번만** 문자열로 만든다.
  - 반올림 모드는 정책 데이터가 정하고, 엔진 `RoundingPolicy`와 같은 중앙 지점(`RatioRounding`)에서만 적용한다.
- **등급은 반올림된 비율로 판정한다.** 확인서에 인쇄된 비율과 등급이 어긋나지 않게 하려는 것이다(예: "1.30"인데 매우높음이 되는 일이 없도록). `TODO(confirm#13)`로 표시하고 설계서에 해석으로 적는다.
- **순위와 단조성**: 순위는 원 측정값 오름차순이다. 측정값이 오르면 비율이 비감소하고, 반올림 비율도 비감소하며, 등급도 비감소한다. 따라서 단조성은 구성상 성립하고 자기 검증이 이를 재확인한다.
- **`TEMP_PRODUCT`**: 데이터 목록에만 두고 v1 측정은 만들지 않는다. 엔진이 임시등록 여부를 알 수단이 없기 때문이다. 보고서에 명시한다.
- **설계서**: "예시 측정 — 정의(모수·기간)는 §11 #13의 외부 확정 사실이며 `measureKey` 레지스트리로 교체"로 적는다.

## 3. 정책 데이터

- **등급 body**: 지시문 구조에 `measureParams`, `minPopulation`, `asOfFutureDaysAllowed`(0, 미래일 거부 파라미터)를 더한다. `unavailableReasons`에는 `INSUFFICIENT_POPULATION`을 추가한다.
- **경계의 포함 여부**: 모든 경계에 `minInclusive`/`maxInclusive`를 **명시적 필수**로 둔다. 지시문 예시는 중간 등급에서 이를 생략했지만, B-13(침묵 기본값 금지)과 E2(빈틈·겹침 판정)를 위해서는 명시가 필요하다.
- **로드 시 검증(E2)**, 위반마다 실패한다:
  - 코드·ordinal 중복, 빈 라벨;
  - 하한 없는 구간과 상한 없는 구간이 정확히 1개씩;
  - 비율 순으로 정렬했을 때 인접 경계의 값이 같고 포함 여부가 상보적(한쪽 포함·한쪽 제외)이어야 한다 — 어기면 빈틈 또는 겹침;
  - ordinal은 비율 순으로 엄격 증가;
  - 임계값은 `BigDecimal` 문자열이어야 하고, 숫자 JSON은 거부.
- **순위 body**: `{tieBreak}` + `STRICT`일 때 `secondaryKeys`(닫힌 어휘 `MEASURE_ASC`, `PRODUCT_KEY_ASC`).
- **`MeasureRegistry.of(List<Measure>)`**: 키 중복·미등록 키면 실패한다. 스프링 빈 목록으로 조립하며, `CalcPipelineConfig`의 명시 목록 패턴과 같다.

## 4. DDL 초안 (계획 ③, Oracle; H2 호환 부분은 공통 V12, Oracle 전용은 vendor V103)

```sql
-- V12__disclosure_grades.sql
CREATE TABLE DISC_GRADING_POLICY (
  policy_version_id VARCHAR2(40) PRIMARY KEY,
  apply_from DATE NOT NULL, apply_to DATE DEFAULT DATE '9999-12-31' NOT NULL,
  status VARCHAR2(12) NOT NULL CHECK (status IN ('DRAFT','ACTIVE','SUPERSEDED')),
  body CLOB NOT NULL,
  created_by VARCHAR2(64) NOT NULL, created_at TIMESTAMP NOT NULL, approved_by VARCHAR2(64), approved_at TIMESTAMP,
  CHECK (apply_to >= apply_from)
);
CREATE TABLE DISC_RANKING_POLICY ( /* 같은 형태 */ );
CREATE TABLE DISC_PRODUCT_GROUP (
  group_code_system VARCHAR2(20) NOT NULL, group_code VARCHAR2(64) NOT NULL, group_name VARCHAR2(200) NOT NULL,
  apply_from DATE NOT NULL, apply_to DATE DEFAULT DATE '9999-12-31' NOT NULL,
  PRIMARY KEY (group_code_system, group_code)
);
CREATE TABLE DISC_PRODUCT_GROUP_MEMBER (
  group_code_system VARCHAR2(20) NOT NULL, group_code VARCHAR2(64) NOT NULL,
  ext_product_key VARCHAR2(129) NOT NULL,              -- 계약의 productKey
  insurer_cd VARCHAR2(10) NOT NULL, product_key VARCHAR2(40) NOT NULL,   -- COMM_RATE 키
  apply_from DATE NOT NULL, apply_to DATE DEFAULT DATE '9999-12-31' NOT NULL,
  PRIMARY KEY (group_code_system, group_code, ext_product_key, apply_from),
  FOREIGN KEY (group_code_system, group_code) REFERENCES DISC_PRODUCT_GROUP
);
CREATE TABLE DISC_GRADE_SNAPSHOT (
  snapshot_id VARCHAR2(20) PRIMARY KEY,                -- GRD-yyyyMMdd-NNNNNN
  tenant_id VARCHAR2(32) NOT NULL, as_of_date DATE NOT NULL, product_group_code VARCHAR2(64) NOT NULL,
  grading_policy_version_id VARCHAR2(40) NOT NULL, ranking_policy_version_id VARCHAR2(40) NOT NULL,
  tie_break VARCHAR2(12) NOT NULL CHECK (tie_break IN ('SHARED_RANK','STRICT')),
  basis_json CLOB NOT NULL, generated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  response_canonical CLOB NOT NULL, response_sha256 CHAR(64) NOT NULL
);
CREATE TABLE DISC_GRADE_SNAPSHOT_ITEM (
  snapshot_id VARCHAR2(20) NOT NULL REFERENCES DISC_GRADE_SNAPSHOT,
  product_key VARCHAR2(129) NOT NULL, item_order NUMBER(4) NOT NULL,
  status VARCHAR2(12) NOT NULL CHECK (status IN ('OK','UNAVAILABLE')),
  ratio_to_avg VARCHAR2(32), grade VARCHAR2(32), grade_label VARCHAR2(64), grade_ordinal NUMBER(3),
  rank_in_set NUMBER(4), tie NUMBER(1), reason VARCHAR2(40),
  PRIMARY KEY (snapshot_id, product_key),
  CHECK ((status = 'OK' AND ratio_to_avg IS NOT NULL AND grade IS NOT NULL AND grade_label IS NOT NULL
          AND grade_ordinal IS NOT NULL AND rank_in_set IS NOT NULL AND tie IS NOT NULL AND reason IS NULL)
      OR (status = 'UNAVAILABLE' AND ratio_to_avg IS NULL AND grade IS NULL AND grade_label IS NULL
          AND grade_ordinal IS NULL AND rank_in_set IS NULL AND tie IS NULL AND reason IS NOT NULL))
);
CREATE TABLE DISC_GRADE_SNAPSHOT_SEQ (seq_date DATE PRIMARY KEY, last_no NUMBER(6) NOT NULL);  -- 일자별 채번(행 잠금)

-- vendor/oracle/V103__disclosure_grades_oracle.sql
-- body·basis_json IS JSON 검사, DISC_GRADE_SNAPSHOT(_ITEM) BEFORE UPDATE OR DELETE 트리거 → RAISE_APPLICATION_ERROR(-20301/-20302)
```

- **스냅샷 ID 채번**: 날짜는 주입된 `Clock`(Asia/Seoul)으로 정한다. 일자별 행을 `SELECT … FOR UPDATE`로 잠그고 +1 하며, 999999를 넘으면 실패한다.
- **응답 순서**: OK 항목을 `rankInSet` 순으로 놓는다(동순위는 `productKey` 순). 그 뒤에 UNAVAILABLE 항목을 요청 순서로 둔다. JCS는 배열 순서를 바꾸지 않으므로 이 순서가 바이트에 고정된다.
- **바이트 동일성**: POST 응답 본문 자체가 저장된 `response_canonical` 바이트다. 따라서 POST와 GET이 바이트 동일하고, GET은 SHA-256을 대조한 뒤 반환한다. 대조가 불일치하면 500이 아니라 `SNAPSHOT_INTEGRITY` 오류를 낸다.
- **테스트 인프라 변경**:
  - `FlywayMigrationTest`의 테이블 목록에 새 테이블을 추가한다.
  - `OracleTestSupport.cleanAll()`은 불변 테이블을 DELETE할 수 없으므로 `TRUNCATE`(DML 트리거 미발동)를 쓴다.

## 5. API (계획 ④ 포함)

- **컨트롤러** (`commission-api` `web`):
  - `DisclosureGradeController`: POST와 GET. 응답은 `application/json` 원 바이트다.
  - 오류는 계약의 `Problem{code,message,details}` 형식으로 내며, 컨트롤러 전용 `@ExceptionHandler`로 처리한다.
  - Ambiguous → 409다. 전역 `ApiExceptionHandler`의 422 매핑은 기존 API용으로 그대로 둔다.
- **요청 검증 (400)**: 다음 경우에 400을 낸다.
  - 빈 배열 또는 중복 `productKey`;
  - 형식 위반;
  - `asOfDate`가 오늘(Clock) + `asOfFutureDaysAllowed`보다 늦음;
  - 상품군이 코드 체계에 없음.
- **직렬화**: `UNAVAILABLE` 항목은 sealed 타입(`OkResult`/`UnavailableResult`) 두 record로 나눈다. 필드가 아예 없으므로 null이 나올 수 없다.
- **계약 복사와 CHECKSUMS**:
  - `commission-system/contracts/api/v1/engine-disclosure.openapi.yaml`에 복사한다.
  - `contracts/CHECKSUMS`(`ga-disclosure`와 같은 `sha256sum` 형식)와 `contracts/UPSTREAM`(`hjryoo-ai/ga-disclosure@<커밋>`)을 둔다.
  - `verifyContractChecksums`(로컬, `check`에 연결)로 로컬 파일과 CHECKSUMS의 일치를 검사한다.
  - `verifyUpstreamContract`(CI 전용)로 공개 저장소의 해당 커밋에서 계약 파일을 받아 SHA-256을 비교한다. `ga-disclosure`가 public이라 토큰이 필요 없다.
- **계약 테스트**: `EngineDisclosureContractTest`는 networknt json-schema-validator로 OpenAPI의 `components.schemas`를 검증한다(oneOf, `additionalProperties:false`).

## 6. 완료 기준 → 테스트

| # | 테스트 |
|---|---|
| E0 | (선행) jqwik 제거: 3개 속성 테스트 대조표, `net.jqwik` 해석 차단 |
| E1 | `GradingPolicyAsDataTest`: 5단계 픽스처와 4단계 픽스처, 같은 입력에서 등급·ordinal이 달라짐. 두 픽스처 diff를 출력하고 `src/main` 해시가 무변경임을 단언 |
| E2 | `GradingPolicyValidationTest`: 겹침·빈틈·ordinal 중복·라벨 누락·포함 여부 누락·숫자 임계값 |
| E3 | `DisclosurePolicyRepositoryContract`(메모리 구현과 Oracle 구현 모두): 2건 → `AmbiguousRuleException`, 0건 → `RuleNotFoundException`, 경계일 |
| E4 | `RankingServiceTest`: 1-2-2-2-5와 전원 `tie=true`, STRICT 순열에서 분리된 항목은 `tie=true` |
| E5 | `MonotonicityPropertyTest`: 시드 고정 500세트 |
| E6 | `GradeResponseSerializationTest`와 계약 테스트: 전부 UNAVAILABLE이어도 200 |
| E7 | `SnapshotRefetchIT`(Oracle): 바이트 동일, 해시 대조, 정책 교체 후 재조회 |
| E8 | `SnapshotImmutabilityIT`: UPDATE·DELETE → ORA-20301/20302 |
| E9 | `RatioFormattingTest`: scale·HALF_UP 경계(x.xx5), 응답·DB·재조회 삼자 동일 |
| E10 | `DisclosureGradeControllerTest`: 401·403·400 전 경로, 409 |
| E11 | `EngineDisclosureContractTest`와 `verifyContractChecksums` |
| E12 | 전체 빌드(빠른 층과 전체 층) |

## 7. 순서

1. **선행 계약 PR**: Q3를 반영한 `ga-disclosure` PR을 먼저 올린다. 그 PR의 병합 커밋이 `UPSTREAM`의 고정 기준이 된다.
2. **E3-0**: jqwik 교체.
3. **정책 모델과 검증**: E1, E2.
4. **순위와 자기 검증**: E4, E5.
5. **DDL·저장소·채번**: E3, E7, E8.
6. **보안·컨트롤러**: E10.
7. **계약과 체크섬**: E6, E11.
8. **설계서 v1.2.2**와 CONTRIBUTING(병합 방식).
9. **보고서**, 태그 `phase-e3`.
