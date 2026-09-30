# Phase E3 지시문 — 엔진 등급·순위 산출 API (`ga-commission-engine`, v1.0)

## 역할과 맥락

당신은 `ga-commission-engine` 저장소의 구현자다. 이 저장소의 정본은 그 저장소의 `docs/설계서.md`(v1.2.0)와 `CLAUDE.md`이며, 그 규약(룰=데이터·기준일 필수·단건 해석 Ambiguous fail-fast·BigDecimal + 중앙 반올림·불변 결과·Testcontainers 선행·문서-코드 동일 커밋)이 그대로 적용된다. 이번 Phase는 **비교설명 확인서 시스템(`ga-disclosure`)이 호출하는 판매수수료 등급·순위 산출 API**를 제공한다. 계약의 정본은 `ga-disclosure` 저장소의 `contracts/api/v1/engine-disclosure.openapi.yaml`이고, 그 파일을 이 저장소의 `contracts/api/v1/`에 복사해 `CHECKSUMS`로 일치를 검사한다(포털 계약과 같은 방식).

이 Phase의 전제는 Phase 15B·16 선행에서 만든 `RankingService`와 교체 가능한 `GradingPolicy`다. 15B 리뷰에서 "3분위 등급은 서식이 아니라 방법론이지만 역시 외부 확정 사실"이라 지적됐고, 이번에 **등급 임계치·모수·기간·동점 규칙을 전부 데이터로** 옮긴다. 완료 시 "5단계를 4단계로 바꾸는 것"이 코드 diff 0으로 흡수돼야 한다.

## 시작 전 보고

구현 계획을 먼저 보여주고 승인 후 진행한다. 계획에는 ① 현재 `RankingService`·`GradingPolicy`의 실제 시그니처와 3분위 로직이 어디에 있는지 ② 이 저장소가 이미 보유한 상품별 판매수수료율 데이터(매출측 요율)의 테이블·키 — 등급의 **모수(measure)** 후보를 여기서 고른다 ③ 스냅샷 테이블 DDL 초안 ④ 계약 파일 복사와 CHECKSUMS 검사 방식이 들어간다.

## 계약 요약 (정본은 openapi.yaml, 여기서는 의미만)

- `POST /internal/v1/disclosure/commission-grades` 요청: `tenantId`, `asOfDate`, `productGroupCode`, `products[]{productKey, insurerCode}`.
- 응답: `snapshotId`, `gradingPolicyVersionId`, `rankingPolicyVersionId`, `tieBreak(SHARED_RANK|STRICT)`, `basis{groupAvgSource, period, groupPopulation}`, `results[]`(status로 분기하는 oneOf — `OK`: `ratioToAvg`(string)·`grade`·`gradeLabel`·`gradeOrdinal`·`rankInSet`·`tie` 전부 필수 / `UNAVAILABLE`: `reason`만 필수, 나머지 6개 필드 **부재**), `generatedAt`.
- `GET /internal/v1/disclosure/commission-grades/{snapshotId}`: 저장된 스냅샷을 **바이트 단위로 동일하게** 반환.
- 응답에 수수료율 원 수치는 없다. `ratioToAvg`는 문자열이며 생성 후 절대 재포맷하지 않는다.

## 작업 목록

### 1. 등급 정책 데이터화 (`GradingPolicy` → 버전 레코드)
- 테이블 `DISC_GRADING_POLICY(policy_version_id, apply_from, apply_to, status, body CLOB(JSON), …)`. 기존 룰 버전 테이블 규약(유효기간·상태·Ambiguous)을 그대로 따른다. 이 저장소에 이미 범용 룰 버전 테이블이 있으면 그것을 쓰고 새 테이블을 만들지 않는다 — 계획에서 밝힐 것.
- `body` 구조(예시값):
  ```json
  {
    "measureKey": "FY_SALES_COMMISSION_RATE",
    "population": { "groupCodeSystem": "PG-V1", "scope": "GA_CONTRACTED_PRODUCTS" },
    "period": { "kind": "TRAILING_QUARTER", "asOfRule": "REQUEST_DATE" },
    "ratioScale": 2, "ratioRounding": "HALF_UP",
    "grades": [
      { "code": "VERY_HIGH", "label": "매우높음", "ordinal": 5, "minRatio": "1.30", "minInclusive": false },
      { "code": "HIGH",      "label": "높음",     "ordinal": 4, "minRatio": "1.10", "maxRatio": "1.30" },
      { "code": "MID",       "label": "보통",     "ordinal": 3, "minRatio": "0.90", "maxRatio": "1.10" },
      { "code": "LOW",       "label": "낮음",     "ordinal": 2, "minRatio": "0.70", "maxRatio": "0.90" },
      { "code": "VERY_LOW",  "label": "매우낮음", "ordinal": 1, "maxRatio": "0.70" }
    ],
    "unavailableReasons": ["NO_RATE_DATA", "NOT_IN_GROUP", "TEMP_PRODUCT", "OUTSIDE_PERIOD"]
  }
  ```
  구간 경계의 포함/제외는 데이터로 표현하고, 구간이 겹치거나 빈틈이 있으면 정책 로드 시 실패한다. 임계치 130/110/90/70은 **코드 어디에도 없다.**
- `measureKey → 측정 함수` 레지스트리(엔진의 Step 레지스트리와 같은 패턴). v1 측정 함수 1개: 이 저장소의 매출측 요율 원장에서 상품별 초년도 판매수수료율을 읽는 것. **수수료율의 정확한 정의(모수·기간)는 외부 확정 사실**(설계서 §11 #13)이므로 측정 함수는 교체 가능하고, 문서에는 "예시 측정"으로 적는다.
- 유사상품군 평균은 `population`으로 정해진 모집단에서 계산한다. 모집단 크기 `groupPopulation`을 basis에 넣는다. 모집단이 정책의 최소 크기(`minPopulation`, 데이터) 미만이면 그 상품군 전체가 `UNAVAILABLE(reason=INSUFFICIENT_POPULATION)` — 이 사유도 데이터 목록에 추가.

### 2. 순위 정책 데이터화 (`RankingService` 세트 내 순위)
- `DISC_RANKING_POLICY` 버전 레코드, body: `{ "tieBreak": "SHARED_RANK" }` 또는 `{ "tieBreak": "STRICT", "secondaryKeys": ["MEASURE_ASC", "PRODUCT_KEY_ASC"] }`.
- 순위는 요청 세트의 `OK` 항목만으로 매긴다. 기준은 측정값 오름차순(수수료가 낮을수록 1순위, 규정 정의). `SHARED_RANK`는 경쟁 순위(1-2-2-4)이고 동값 항목 전부 `tie=true`. `STRICT`는 2차 키로 분리해 1..m 순열이며, 분리된 항목은 `tie=true`로 표시한다(원래 동값이었음을 남긴다).
- 단조성은 구성상 보장된다(등급도 순위도 같은 측정값에서 나온다). 그래도 응답 생성 후 **자기 검증**을 한다: `rankInSet` 오름차순에서 `gradeOrdinal` 비감소, 동순위끼리 `gradeOrdinal` 동일. 실패하면 500이 아니라 스냅샷을 만들지 않고 명시 오류(정책 데이터 모순 신호).

### 3. 스냅샷 영속화와 재조회
- `DISC_GRADE_SNAPSHOT(snapshot_id, tenant_id, as_of_date, product_group_code, grading_policy_version_id, ranking_policy_version_id, tie_break, basis_json, generated_at, response_canonical CLOB, response_sha256)` + `DISC_GRADE_SNAPSHOT_ITEM(snapshot_id, product_key, status, ratio_to_avg VARCHAR2, grade, grade_label, grade_ordinal, rank_in_set, tie, reason)`.
- **`ratioToAvg` 문자열은 생성 시점에 한 번만 만든다**: `BigDecimal` 측정값 → 정책의 `ratioScale`·`ratioRounding`으로 반올림 → `toPlainString()` → 저장. 이후 어떤 경로도 이 문자열을 숫자로 되돌려 다시 포맷하지 않는다.
- 재조회 API는 `response_canonical`을 **그대로** 반환한다(재직렬화 금지). 응답 생성 시 canonical JSON(키 정렬·공백 없음)을 만들고 SHA-256을 저장하며, 재조회 시 저장된 해시와 대조한 뒤 반환한다.
- `snapshot_id` 형식 `GRD-{yyyyMMdd}-{6자리 시퀀스}`, 테넌트 인스턴스 내 유일. 스냅샷은 불변(UPDATE·DELETE 트리거 거부 — 이 저장소의 불변 결과 규약과 동일).

### 4. API·보안
- `commission-api`에 컨트롤러 2개. `/internal/**`는 서비스 토큰(기존 규약). 요청의 `tenantId`가 이 인스턴스의 테넌트와 다르면 403 — 엔진은 테넌트별 인스턴스이므로 이 검사가 격리의 전부다.
- 응답 직렬화 규칙: `UNAVAILABLE` 항목은 6개 필드를 **직렬화하지 않는다**(null 출력 금지). 계약의 `additionalProperties: false`와 맞물린다.
- 요청 검증: `products[]` 중복 키 거부, 빈 배열 거부, `asOfDate` 미래 거부(정책 파라미터), `productGroupCode`가 모집단 코드 체계에 없으면 400.

### 5. 계약 테스트 (provider)
- `contracts/api/v1/engine-disclosure.openapi.yaml`을 `ga-disclosure`에서 복사하고 `CHECKSUMS`에 SHA-256을 기록. CI가 두 저장소의 체크섬 일치를 검사할 수 있게 태스크를 둔다(포털 계약과 같은 방식).
- 응답이 스키마를 통과하는지(oneOf 분기 포함), `UNAVAILABLE` 항목에 등급 필드가 없는지, 재조회가 원 응답과 **바이트 동일**한지, `tieBreak` 두 값 모두에서 순위 규칙이 계약대로인지.

### 6. 설계서 갱신
- `docs/설계서.md`를 v1.2.1로: 비교설명 등급·순위 API 절 신설(계약 요약·정책 데이터 구조·스냅샷 불변·재조회 규약), §11 #13에 "등급 산정 기준(분위/모수/기간)·동점 규칙"이 외부 확정 사실이며 전부 정책 데이터임을 명시. 같은 커밋.

## 완료 기준 (전부 테스트로 증명)

| # | 기준 | 증명 방법 |
|---|---|---|
| E1 | 등급 정책을 5단계→4단계(임계치 변경)로 **데이터만** 교체해 같은 입력의 등급·ordinal이 바뀜, 소스 diff 0 | `GradingPolicyAsDataTest` — 두 정책 픽스처, 프로덕션 소스 무변경 단언 |
| E2 | 구간 겹침·빈틈·ordinal 중복·라벨 누락 정책은 로드 시 실패 | `GradingPolicyValidationTest` |
| E3 | 같은 기준일에 ACTIVE 정책 2건 → Ambiguous 실패, 0건 → 명시 실패 | 기존 룰 해석기 규약 재사용 테스트 |
| E4 | `SHARED_RANK`: 동값 3개 포함 세트에서 1-2-2-2-5, 전부 `tie=true`; `STRICT`: 2차 키로 1..m 순열, 분리된 항목 `tie=true` | `RankingServiceTest` |
| E5 | 순위 오름차순 ⇒ ordinal 비감소, 동순위 ⇒ ordinal 동일 — 시드 고정 무작위 세트 500건 | `MonotonicityPropertyTest`(jqwik 금지, 이 저장소의 속성 테스트 방식 유지) |
| E6 | `UNAVAILABLE` 항목은 순위 세트에서 제외되고 응답에 6개 필드가 부재; 전부 UNAVAILABLE인 세트도 200 | `GradeResponseSerializationTest`, 계약 테스트 |
| E7 | 재조회가 바이트 동일(해시 대조 포함); 정책 버전이 바뀐 뒤 재조회해도 옛 스냅샷 문자열 불변 | `SnapshotRefetchIT`(Oracle Testcontainers) |
| E8 | 스냅샷 UPDATE·DELETE 거부 | `SnapshotImmutabilityIT` |
| E9 | `ratioToAvg`가 정책의 scale·rounding대로 한 번 생성되고, 응답·스냅샷·재조회 삼자 동일 | `RatioFormattingTest` |
| E10 | 테넌트 불일치 403, 중복 키·빈 배열·미래일 400 | `DisclosureGradeControllerTest` |
| E11 | 계약 스키마 통과 + `CHECKSUMS` 일치 태스크 | `EngineDisclosureContractTest` |
| E12 | 기존 전 테스트 무손상 | 빌드 로그 |

## 하지 말 것

- 포털용 아웃박스·이벤트 피드(Phase E1)·시뮬레이션 API(E2) — 별도 지시.
- 수수료율 원 수치를 응답에 넣는 것. 임계치·라벨·사유 코드를 상수로 두는 것.
- `ratioToAvg`를 `number`로 직렬화하거나, 재조회 시 재직렬화하는 것.
- `ga-disclosure` 계약 파일을 이 저장소에서 수정하는 것. 바꿔야 하면 사유를 보고하고 `ga-disclosure` 쪽에서 먼저 고친다.

## 보고 형식

1. 태그 `phase-e3`, 커밋 목록, 변경 파일 트리.
2. 선택한 측정 함수(measure)의 정의와 데이터 출처, 이것이 예시 측정임을 설계서에 어떻게 적었는지.
3. 테스트 요약, 완료 기준 E1~E12 증거. E1은 두 정책 픽스처의 diff와 소스 diff(0).
4. 설계서와 달리 구현했거나 해석한 지점.
5. `ga-disclosure` 계약에 대한 변경 요청(있으면).
