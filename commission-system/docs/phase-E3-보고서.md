# Phase E3 완료 보고 — 비교설명 판매수수료 등급·순위 API

> 지시문 `docs/phase-E3-지시문.md` v1.0, 승인 계획 `docs/phase-E3-계획.md`(Q1~Q9 "권장승인", 2026-09-29).
> 브랜치 `work/phase-e3`, 태그 `phase-e3`. 설계서 v1.2.2.

## 1. 태그·커밋·변경 파일

| 커밋 | 내용 |
|---|---|
| `2fa4e17` | docs(e3): 지시문·승인 계획 |
| `c544266` | test(e3-0): jqwik → 시드 고정 JUnit 파라미터화, `net.jqwik` 해석 차단 |
| `a4c4585` | feat(e3): 등급·순위 API — 정책 데이터, 불변 스냅샷, 서비스 토큰, V12/V103, 설계서 v1.2.2(§6.7 신설) |
| `8b87cee` | docs(e3): merge commit 규약·`work/phase-*` 브랜치, README의 jqwik 표기 정리 |
| `d001f0d` | test(e3): provider 계약 테스트, `contracts/`(복사본)·CHECKSUMS·UPSTREAM, CI 원본 대조·jqwik 스캔 |
| (이 커밋) | docs(e3): 보고서, 설계서 테스트 건수 |

변경 파일(117개, `git diff --stat main..HEAD`) — 요지:

```
commission-system/
├── settings.gradle.kts, build.gradle.kts            # commission-disclosure 추가, jqwik 제거·차단, 계약 체크섬 태스크 3종
├── gradle/verification-metadata.xml                 # jqwik 5개 제거, JCS·networknt·itu·yaml 추가(Maven Central sha1 대조 일치)
├── contracts/                                       # ga-disclosure 복사본(수정 금지): api/v1/engine-disclosure.openapi.yaml, CHECKSUMS, UPSTREAM
├── commission-domain/src/testFixtures/…/SeededCases.java   # (E3-0) 시드 고정 생성기
├── commission-domain|limit|deferral/src/test/…PropertyTest.java  # (E3-0) jqwik 교체
├── commission-disclosure/ (신규, Spring 무의존)
│   ├── main: DisclosureGradeService, GradeRequest, GradeResult, RatioToAvg, SelfCheck, MeasurePeriod, Transactions
│   │         policy/(PolicyLoader·PolicyResolver·PolicySource·GradingPolicySpec·RankingPolicySpec·GradeBand·RatioBound·닫힌 어휘 enum 6종)
│   │         measure/(Measure·MeasureRegistry·SalesRateLedger·FySalesCommissionRateMeasure)  group/  rank/SetRanker  snapshot/
│   ├── testFixtures: 인메모리 어댑터 4종, DisclosurePolicyRepositoryContract, GradeScenario, 정책 픽스처 JSON 4개
│   └── test: 11개 클래스
├── commission-api:   web/DisclosureGradeController, disclosure/RankingService.rankInSet, TercileGradingPolicy(double 제거) + 테스트
├── commission-infra: V12__disclosure_grades.sql, vendor/oracle/V103__disclosure_grades_oracle.sql, DisclosureGradeMapper,
│                     Oracle 어댑터 4종, OraclePersistence 팩토리 + IT 3종(계약·재조회·불변)
├── commission-app:   security/(InstanceProperties·ServiceTokenFilter·SecurityConfig /internal 체인), config/DisclosureGradeConfig,
│                     application.yml(app.tenant-id·service-token-sha256), BootSmokeIT E2E 추가, ServiceTokenFilterTest
└── docs/설계서.md (v1.2.2), phase-E3-{지시문,계획,보고서}.md
(git 루트) .github/workflows/ci.yml, CONTRIBUTING.md, .github/pull_request_template.md, README.md
```

## 2. 측정 함수(measure) — 예시 측정

- **정의**: `FY_SALES_COMMISSION_RATE` = 상품군 소속 상품의 **매출측(INBOUND = 보험사→GA) 요율** `COMM_RATE`에서 `comm_type`·`installment_no`가 정책 `measureParams`(v1 픽스처: `FY_COMM`, 회차 NULL = 초년도 판매수수료)와 일치하고 `status='ACTIVE'`인 행을 **측정 기준일**로 읽은 값(`NUMBER(9,6)`, `BigDecimal`).
- **측정 기준일**: `period = TRAILING_QUARTER / REQUEST_DATE` — 요청 기준일이 속한 분기의 직전 완료 분기 말일(2026-09-23 → 2026-06-30, `basis.period = "2026Q2"`). 분기 말일 당일은 그 분기가 아직 끝나지 않은 것으로 본다.
- **데이터 출처**: 기존 `RuleRepository.findRate(INBOUND, …)`(기준일 필수·2건 Ambiguous) 재사용 + 기간 무관 존재 여부 조회 1건(`OUTSIDE_PERIOD` vs `NO_RATE_DATA`). 외부 `productKey`(129자) → 원장 키 `(insurer_cd 10자, product_key 40자)`는 신규 데이터 `DISC_PRODUCT_GROUP_MEMBER`로만 잇는다(문자열 추측 없음, 매핑 없으면 `NOT_IN_GROUP`).
- **모집단·비율**: 측정 기준일에 측정값이 있는 소속 상품(n = `groupPopulation`, `groupAvgSource = ENGINE_LEDGER`). 비율 = 측정값 × n / Σ — `RatioToAvg`에서 **나눗셈·반올림 1회**(자릿수·모드는 정책). n < `minPopulation` 또는 Σ = 0이면 요청 전체 `INSUFFICIENT_POPULATION`.
- **설계서 표기**: §6.7 "측정(모수) — 예시 측정" 문단과 §11 #13 개정 — "판매수수료율의 정확한 정의(모수·기간)는 외부 확정 사실(§11 #13)이므로 이 함수는 예시이며 교체 대상", "v1 `FY_SALES_COMMISSION_RATE`는 **예시 측정**". 코드 주석(`FySalesCommissionRateMeasure` 클래스 Javadoc)도 같은 문구.

## 3. 테스트 요약

`./gradlew clean build --rerun-tasks --no-build-cache` (로컬, Java 21 툴체인, Docker Desktop): **BUILD SUCCESSFUL, 9,111건 / 실패 0 / 스킵 0**.

| 모듈 | 태스크 | 건수 | 비고 |
|---|---|---:|---|
| commission-domain | test | 5,060 | MoneyPropertyTest 5,031(속성 5종 × (1,000 + 경계값)) + SeededCasesTest 4 |
| commission-limit | test | 3,021 | LimitLedgerPropertyTest 3,014 |
| commission-disclosure (신규) | test | 572 | MonotonicityPropertyTest 500 포함 |
| commission-deferral | test | 111 | DeferralSplitPropertyTest 104 |
| commission-rule | test | 66 | |
| commission-settlement | test | 52 | |
| commission-api | test | 41 | +21(컨트롤러 16, RankingServiceTest 5) |
| commission-calc | test | 32 | |
| commission-clawback | test | 12 | |
| commission-app | test | 7 | BootSmokeIT 2(Oracle 풀 컨텍스트, E2E 1건 추가), ServiceTokenFilterTest 2 |
| commission-inbound / batch / shadow / recon | test | 7 / 6 / 3 / 2 | |
| commission-infra | test (H2) | 2 | FlywayMigrationTest 테이블 목록 +7 |
| commission-infra | integrationTest (Oracle) | 117 | 기존 102 + 신규 15 |

- E3-0 직후(기능 추가 전) 기준선: 8,500건 전부 통과(스킵 0) — 기존 테스트 무손상의 비교 기준. jqwik 원본은 한 번도 실행하지 않았다(아래 §7).
- `net.jqwik`: 전 프로젝트 `dependencies` 출력 6,644행에서 0건. 빌드 로그에 도구 출력발 지시문 없음(§8).

## 4. 완료 기준 증거

| # | 기준 | 증거 |
|---|---|---|
| E0 | (선행) jqwik 제거 | `c544266`. 대조표 §7. `net.jqwik` 재추가 시 해석 단계 실패(주입 I9) |
| E1 | 5단계→4단계 데이터만 교체로 등급·ordinal 변경, 소스 diff 0 | `GradingPolicyAsDataTest.임계치_데이터만_바꿔도_등급과_ordinal이_바뀐다` — 같은 빌드·같은 클래스로 두 정책 픽스처 실행: 1.15 → HIGH/4 vs MID/3(나머지 비율·순위 동일). 픽스처 diff는 아래. 소스 쪽: `DisclosureSourceRulesTest.임계치_라벨_등급코드_사유코드는_프로덕션_소스의_문자열_리터럴에_없다`(disclosure·api·infra·app `src/main` 전체 스캔, 주입 I3로 작동 확인) |
| E2 | 겹침·빈틈·ordinal 중복·라벨 누락 → 로드 실패 | `GradingPolicyValidationTest` 25건 — 결함 21종(겹침 2·빈틈 3·무한 구간 수·ordinal 중복·역전·라벨 누락/공백·코드 중복·포함 여부 누락·숫자 임계값·min≥max·빈 등급·모르는 키·사유 매핑 누락·모르는 원인·UNNECESSARY·minPopulation·기간 종류) + 정상·JSON 형식·순위 정책·MID 제거 |
| E3 | ACTIVE 2건 Ambiguous, 0건 명시 실패 | `DisclosurePolicyRepositoryContract`(3건)를 `InMemoryDisclosurePolicyRepositoryTest`와 `OracleDisclosurePolicyRepositoryIT`가 상속 — 엔진 `AmbiguousRuleException`·`RuleNotFoundException` 재사용, 유효기간 양끝 포함. Oracle: 같은 apply_from ACTIVE 중복은 `UX_DISC_GRADING_ACTIVE`가 거부, body `IS JSON` |
| E4 | SHARED_RANK 1-2-2-2-5 전부 tie / STRICT 1..m, 분리 항목 tie | `RankingServiceTest.SHARED_RANK는_경쟁_순위_1_2_2_2_5_동값은_전부_tie`, `STRICT는_2차_키로_1부터_m_순열_분리된_항목은_tie`(측정값 스케일 무관 비교 포함) |
| E5 | 단조성 500건 | `MonotonicityPropertyTest` 500건(시드 `0x5EED_E305`, 소속 3~20·요청 1~10·비소속 0~2, 0.05 격자로 동값 빈발, 등급 정책 2 × 동점 규칙 2 무작위) — 서비스 자기 검증과 독립적으로 응답 JSON만 보고 판정 |
| E6 | UNAVAILABLE 제외·6필드 부재, 전부 UNAVAILABLE 200 | `GradeResponseSerializationTest`(필드 부재·null 없음·순위 1..m에서 제외), `DisclosureGradeControllerTest.모르는_필드는_무시하고_전부_산출불가도_200`, `EngineDisclosureContractTest.전부_산출불가_응답도_통과한다` |
| E7 | 재조회 바이트 동일(해시 대조), 정책 교체 후 불변 | `SnapshotRefetchIT`(Oracle): `재조회는_바이트_동일하고_저장_해시와_일치한다`(UTF-8 바이트 비교, DB `response_sha256` = 재계산), `정책이_바뀐_뒤에도_옛_스냅샷_문자열은_그대로다`(정책 SUPERSEDED+새 4단계 ACTIVE+요율 UPDATE 후에도 동일), `없는_스냅샷과_다른_테넌트는_404_원문_변조는_무결성_오류`. 컨트롤러 층: `발급_200과_재조회_바이트_동일`, 풀 컨텍스트: `BootSmokeIT.비교설명_등급_API_E2E` |
| E8 | 스냅샷 UPDATE·DELETE 거부 | `SnapshotImmutabilityIT` 7건 — 헤더 UPDATE 2·DELETE 2(0행 대상 포함) → ORA-20301, 항목 UPDATE 2·DELETE 1 → ORA-20302 |
| E9 | ratioToAvg 1회 생성, 응답·스냅샷·재조회 동일 | `RatioFormattingTest` 9건(HALF_UP/HALF_EVEN/DOWN x.xx5 경계, 이중 반올림 없음 1/3 사례, scale 3, 삼자 동일·문자열 타입) + `SnapshotRefetchIT`(DB `ratio_to_avg` VARCHAR2 원문과 삼자 동일). 소스 규칙: `divide`/`setScale`/`round`는 `RatioToAvg.java` 한 곳(`나눗셈과_반올림은_RatioToAvg_한_곳뿐`) |
| E10 | 403·400 | `DisclosureGradeControllerTest` 16건(403 TENANT_MISMATCH, 중복 키·빈 배열·미래일 AS_OF_IN_FUTURE·상품군 UNKNOWN_PRODUCT_GROUP·형식 7종 → 400, 422 NO_POLICY, 409 AMBIGUOUS_POLICY, 404) + `BootSmokeIT` 401(토큰 없음·틀림·Basic 사용자)·403 |
| E11 | 계약 스키마 + CHECKSUMS | `EngineDisclosureContractTest` 6건(실제 응답 OK/UNAVAILABLE·SHARED/STRICT·전부 산출불가 통과, 망가뜨린 응답 5종 거부, 요청 스키마, CHECKSUMS·UPSTREAM·1.1.0) + `verifyContractChecksums`(check) + `verifyUpstreamContract`(CI: `hjryoo-ai/ga-disclosure@af3399c`의 원본과 SHA-256 일치) |
| E12 | 기존 전 테스트 무손상 | §3 — 기존 모듈 건수가 E3-0 기준선과 같고(domain 5,060·limit 3,021·deferral 111·rule 66·…·infra IT 102), 실패 0. 기존 테스트 수정은 추가뿐: `FlywayMigrationTest` 테이블 목록 +7, `OracleTestSupport.cleanAll` 대상 +4, `BootSmokeIT` 설정 2개 + E2E 메서드 1개 |

**E1 픽스처 diff** (`grading-5-step.json` → `grading-4-step.json`, 그 밖의 키는 동일):

```diff
-    { "code": "VERY_HIGH", "label": "매우높음", "ordinal": 5, "minRatio": "1.30", "minInclusive": false },
-    { "code": "HIGH", "label": "높음", "ordinal": 4, "minRatio": "1.10", "minInclusive": false, "maxRatio": "1.30", "maxInclusive": true },
-    { "code": "MID", "label": "보통", "ordinal": 3, "minRatio": "0.90", "minInclusive": false, "maxRatio": "1.10", "maxInclusive": true },
-    { "code": "LOW", "label": "낮음", "ordinal": 2, "minRatio": "0.70", "minInclusive": false, "maxRatio": "0.90", "maxInclusive": true },
-    { "code": "VERY_LOW", "label": "매우낮음", "ordinal": 1, "maxRatio": "0.70", "maxInclusive": true }
+    { "code": "HIGH", "label": "높음", "ordinal": 4, "minRatio": "1.20", "minInclusive": false },
+    { "code": "MID", "label": "보통", "ordinal": 3, "minRatio": "1.00", "minInclusive": false, "maxRatio": "1.20", "maxInclusive": true },
+    { "code": "LOW", "label": "낮음", "ordinal": 2, "minRatio": "0.80", "minInclusive": false, "maxRatio": "1.00", "maxInclusive": true },
+    { "code": "VERY_LOW", "label": "매우낮음", "ordinal": 1, "maxRatio": "0.80", "maxInclusive": true }
```

**소스 diff 0**: 두 실행은 같은 JVM·같은 컴파일 결과로 돈다(정책 저장소에 넣는 파일만 다름). 프로덕션 소스에는 두 픽스처의 임계치·코드·라벨·사유 코드가 문자열 리터럴로 하나도 없다(스캔 테스트, 위반 주입 I3 → 실패 확인).

## 5. 설계서와 달리 구현했거나 해석한 지점

1. **계획 Q1~Q9 그대로**: jqwik 선행 교체(E3-0), `/internal/**` 서비스 토큰·인스턴스 테넌트 신설(지시문의 "기존 규약"은 없었다), 계약 401·403·422 추가 요청(ga-disclosure PR #2), 외부 키 매핑 테이블, 정책 테이블 신설(범용 룰 버전 테이블 없음), 비교공시 경로 보존 + 3분위 double만 정수식, 설계서 v1.2.2(v1.2.1은 이미 존재), merge commit, JCS 라이브러리 직접 사용(`platform-canonical`은 Java 25 바이트코드라 Java 21 엔진이 쓸 수 없음).
2. **새 모듈 `commission-disclosure`** — 계획은 `commission-api`의 패키지였지만 그러면 Oracle 어댑터(infra)가 웹 모듈(api)에 의존하게 된다. 포트·로직을 Spring 무의존 모듈로 두고 api(컨트롤러)·infra(어댑터)가 그것을 쓴다. 설계서의 "RankingService에 세트 내 순위 추가"는 `RankingService.rankInSet`이 `SetRanker`에 위임하는 형태로 지켰다.
3. **`unavailableReasons`를 목록 → 맵(원인 → 사유 코드)**: 목록이면 엔진이 "요율 없음" 상황에 `"NO_RATE_DATA"`를 내려면 그 문자열을 코드에 둬야 한다(지시문 "사유 코드를 상수로 두는 것" 금지와 충돌). 원인(`UnavailableCause`, 닫힌 어휘)은 코드, 응답 문자열은 데이터.
4. **경계 포함 여부 필수**: 지시문 예시는 중간 등급에서 `minInclusive`/`maxInclusive`를 생략했지만 B-13(침묵 기본값 금지)과 빈틈·겹침 판정을 위해 모든 경계에 필수로 했다. 픽스처는 (min, max] 규약.
5. **정책 body 추가 키**: `measureParams`, `population.minPopulation`, `asOfFutureDaysAllowed`(미래일 거부 = 정책 파라미터).
6. **등급은 반올림 비율로 판정**(`TODO(confirm#13)`), 순위는 원 측정값. 단조성은 구성상 성립.
7. **Σ = 0이면 `INSUFFICIENT_POPULATION`**(평균이 정의되지 않음). 새 사유 코드를 만들지 않았다.
8. **`TEMP_PRODUCT`는 만들지 않는다** — 엔진이 임시등록 여부를 알 입력이 없다(요청에 플래그 없음). 데이터 목록 호환만.
9. **오류 판정 순서**: 형식(400) → 테넌트(403) → 정책 해석(409/422) → 미래일·상품군(400). 미래일 허용 폭이 정책 파라미터라서, **기준일에 정책이 아예 없는 먼 미래일은 400이 아니라 422 `NO_POLICY`**가 된다.
10. **추가 오류 코드**: 422 `INVALID_POLICY`(DB의 정책 body 결함 — 로드 검증 실패), 500 `SNAPSHOT_INTEGRITY`(재조회 해시 불일치), 400 `AS_OF_IN_FUTURE`·`UNKNOWN_PRODUCT_GROUP`. 계약 Problem.code는 자유 문자열이라 스키마 위반은 아니지만 계약 설명에 없는 코드다(§6 요청).
11. **GET의 403**: GET에는 테넌트 입력이 없어 테넌트 불일치 403을 만들 수 없다 — 다른 테넌트 스냅샷은 계약대로 404. 계약의 GET 403은 현재 인가 거부(ROLE 없음) 경로로만 가능하며 실제로는 발생하지 않는다(§6 요청).
12. **요청 `insurerCode`는 형식만 검사**하고 매핑과 대조하지 않는다(외부 코드 체계가 엔진 `insurer_cd`와 다를 수 있음).
13. **부록 B-1 개정**: "반올림은 `RoundingPolicy`만"에 예외 1곳(무차원 비율 `RatioToAvg`, 모드·자릿수는 정책 데이터) — 설계서에 명시, 소스 스캔으로 위치 고정.
14. **정책 겹침의 DB 이중화는 같은 `apply_from`만**(V103 함수 기반 유니크 — COMM_RATE·INCENTIVE와 같은 수준). 개시일이 다른 겹침은 해석기의 Ambiguous가 막는다(ga-disclosure처럼 범위 배타 제약은 Oracle에 없음).
15. **채번 첫 행 경합**: 같은 날 첫 발급 2건이 동시에 오면 한쪽이 PK 위반으로 실패한다(재시도는 호출자 몫, 이후는 행 잠금으로 직렬화). 재시도 러너는 만들지 않았다.
16. **테스트 하네스**: 스냅샷 테이블은 불변이라 `cleanAll`이 비우지 않고, 채번 표도 비우지 않는다(같은 날 번호 재사용 → 기존 스냅샷과 PK 충돌 방지). 테스트는 번호 절대값에 기대지 않는다(인메모리 제외).
17. **Docker 부재 실패 확인 방식**: 병렬로 도는 ga-disclosure 세션이 같은 Docker를 쓰므로 데몬을 멈추지 않고 `DOCKER_HOST`/소켓 오버라이드로 확인 — `SnapshotImmutabilityIT`·`SnapshotRefetchIT` 4건 전부 실패·스킵 0(원인: `ContainerLaunchException`, ryuk 기동 실패). 데몬 정지 조건의 실측은 아니다.
18. **엔진 저장소에는 CLAUDE.md가 없다** — 지시문이 가리킨 규약은 CONTRIBUTING.md와 설계서 부록 B로 적용했다.
19. **`contracts/UPSTREAM`은 ga-disclosure PR #2의 head 커밋 `af3399c`를 고정**한다. PR #2가 이 보고 시점까지 병합되지 않았다. merge commit으로 병합되면 이 커밋은 main에서 도달 가능하므로 고정은 유효하다 — 병합 후 병합 커밋으로 바꿀지는 결정 사항(§6).

## 6. ga-disclosure 계약에 대한 변경 요청

- **이미 반영(ga-disclosure PR #2, 계약 1.1.0)**: 두 연산 401·403, POST 422(`NO_POLICY`, `POLICY_SELF_CHECK_FAILED`). E3 계획 Q3.
- **추가 요청(후속, 설명·응답 추가만 — 호환)**:
  1. POST 422 설명에 `INVALID_POLICY`(정책 데이터 결함) 추가.
  2. GET에 500 `SNAPSHOT_INTEGRITY`(저장 원문 해시 불일치 — 반환하지 않음) 추가.
  3. POST 400 설명에 `AS_OF_IN_FUTURE`·`UNKNOWN_PRODUCT_GROUP` 코드 명시.
  4. GET 403 정리: GET에는 테넌트 입력이 없으므로 "다른 테넌트 = 404"만 남기고 403은 "토큰은 유효하나 인가 거부"로 설명을 바꾸거나 삭제.

## 7. jqwik 교체 대조표 (E3-0)

jqwik 원본은 한 번도 실행하지 않았다(교체를 먼저 하고 빌드). 정의역은 원본과 같고, 경계값은 jqwik의 edge case 대신 명시했다. jqwik의 축소(shrinking)는 없고 대신 실패 케이스 이름에 시드·인덱스가 찍혀 그대로 재현된다.

| 속성 | jqwik 원본 | 교체(시드) | 정의역 | 시행 수 |
|---|---|---|---|---|
| Money 덧셈 교환법칙 | `@Property`(기본 1,000) | `pairs` `0x5EED_E301` | a,b ∈ [−1e9, 1e9] | 1,000 + 경계 6 |
| Money 빼기 = 덧셈 역원 | 〃 | `pairs` | 〃 | 1,006 |
| 절사 ≤ 참값, 오차 < 1원 | 〃 | `amountAndRate` `0x5EED_E303` | amount ∈ [0, 1e9], rate ∈ [0, 3] scale 6 | 1,000 + 7 |
| 반올림 오차 ≤ 0.5원 | 〃 | `amountAndRate` | 〃 | 1,007 |
| negate 두 번 = 원래 | 〃 | `singles` `0x5EED_E302` | [−1e9, 1e9] | 1,000 + 5 |
| 게이트 전기는 한도 불초과 | 〃 | `gateRequests` `0x5EED_E311` | List size 1~50, 원소 0~2,000,000 | 1,000 + 6 |
| 환수 섞여도 불변식 | 〃 | `gateRequestsWithDeductions` `0x5EED_E312` | size 1~40, 0~1,500,000, deductEvery 2~5 | 1,000 + 5 |
| 한도 초과 직접 전기 거부 | 〃 | `excesses` `0x5EED_E313` | 1~10,000,000 | 1,000 + 3 |
| 분급 스케줄 합 = 이연 원금 | `@Property(tries = 100)` | `premiums` `0x5EED_E321` | 10,000~10,000,000 | 100 + 4 |

차단: 루트 빌드 `resolutionStrategy.eachDependency`가 `net.jqwik` 요청을 `GradleException`으로 거부(직접·전이 모두). CI는 전 프로젝트 의존성 출력에서 `net.jqwik` 부재를 이중 확인.

## 8. 위반 주입 기록 (주입 → 실패 확인 → 되돌림)

| # | 주입 | 결과 |
|---|---|---|
| I1 | `PolicyLoader`의 구간 연속성 검사 제거 | `GradingPolicyValidationTest` 8건 실패 |
| I2 | `SelfCheck`의 ordinal 단조 검사 무력화 | `DisclosureGradeServiceTest` 1건 실패(자기 검증 → 스냅샷 미발급) |
| I3 | 프로덕션 소스에 `"1.30"` 리터럴 | `DisclosureSourceRulesTest` 1건 실패 |
| I4 | 서비스에 두 번째 반올림(`sum.divide(n, 2, HALF_UP)`) | `DisclosureSourceRulesTest` 1건 실패 |
| I5 | 테넌트 검사 제거 | `DisclosureGradeControllerTest` 1건 실패(403 기대) |
| I6 | UNAVAILABLE에 `rankInSet: null` 출력 | 계약·직렬화 테스트 4건 실패 |
| I7 | 로컬 계약 파일 1바이트 수정(`version: 1.1.1`) | `verifyContractChecksums` 실패, `EngineDisclosureContractTest` 1건 실패, `verifyUpstreamContract` 실패 |
| I8 | 항목 불변 트리거 본문을 `NULL;`로 | `SnapshotImmutabilityIT` 3건(항목 UPDATE·DELETE) 실패 |
| I9 | `net.jqwik:jqwik:1.10.1` 재추가(`compileTestJava`만 — 테스트 미실행) | 해석 실패 `net.jqwik is banned (Phase E3-0)` |
| I10 | 비율 문자열을 `stripTrailingZeros()`로 | `RatioFormattingTest` 5건 실패 |

기록상 정정: 첫 주입 배치에서 I8(트리거 이름만 바꿔 실제로는 거부가 남음)과 I9(`dependencies` 태스크는 해석 실패를 표시만 하고 종료 코드 0)는 주입 자체가 잘못돼 "잡히지 않음"으로 나왔다 — 위 표는 올바르게 다시 한 결과다. 또 첫 배치의 되돌림(`git checkout -- .`)이 커밋 전이던 루트 빌드의 계약 태스크를 함께 되돌려, I7을 커밋 후 다시 했다(되돌린 태스크는 복원해 커밋 `d001f0d`).

도구·라이브러리 출력에서 지시문 형태의 문장은 발견되지 않았다(빌드 로그 검색). jqwik은 실행하지 않았다.

## 9. 다음 질문

1. **등급 판정 기준 비율**: 반올림 비율(현행, 인쇄값과 일치) vs 원 비율 — `TODO(confirm#13)`.
2. **정책·상품군 데이터의 운영 경로**: `DISC_*_POLICY`·`DISC_PRODUCT_GROUP(_MEMBER)`는 지금 SQL 시드뿐이다. 요율처럼 승인 워크플로(트리밍/SUPERSEDE·변경 감사)와 관리 API를 둘지, ga-disclosure 카탈로그(Phase 2)의 상품군 파일과 같은 원본에서 수입할지.
3. **비교공시 경로의 3분위 등급**을 비교설명과 같은 데이터 정책으로 옮길지(서식 확정 시).
4. **서비스 토큰 운영**: 발급·회전 주체, ga-disclosure 쪽 보관(Phase 3 엔진 클라이언트), mTLS 병행 시점.
5. **채번 첫 행 경합 재시도**를 엔진이 할지(현재 호출자 재시도).
6. **UPSTREAM 고정 대상**: PR #2 head 커밋 유지 vs 병합 커밋으로 교체.
