# Phase E3.1 보고서 — E3 수용 심사 후속 (2026-09-30)

> 심사 회신 `docs/phase-E3-수용심사.md` §3(항목 1~8)·§4-4. 브랜치 `work/phase-e3.1`(main `98d5e50`에서 분기). ga-disclosure 계약 1.2.0 병합 커밋 `9379be96`(ga-disclosure PR #4) 이후 시작.

## 1. 항목별 커밋·테스트

| # | 항목 | 커밋 | 테스트(클래스#메서드) |
|---|---|---|---|
| — | 심사 회신 보관 | `e5ffb29` | — |
| 1 | UPSTREAM → `hjryoo-ai/ga-disclosure@9379be96d28f02bf5c3f11a8810210106cc66165`, 계약 1.2.0 재복사, CHECKSUMS 재생성 | `ed00f37` | `EngineDisclosureContractTest#계약_파일은_CHECKSUMS와_일치하고_UPSTREAM이_출처를_고정한다`(1.2.0), `./gradlew verifyUpstreamContract` → `contracts/api/v1/engine-disclosure.openapi.yaml == hjryoo-ai/ga-disclosure@9379be96… (58d5b45e…)` |
| 2 | 상품 키 규칙(40자·패턴, insurerCode) 요청 검증 400, `DISC_PRODUCT_GROUP_MEMBER.ext_product_key` 129→40(V14) | `ed00f37`(검증·V14), `c0142e0`(서비스·컨트롤러·DDL 테스트) | `EngineDisclosureContractTest#엔진_요청_검증과_계약_스키마의_키_판정이_같다`(14 키 — 수용 4·거부 10, 엔진 판정 = 계약 스키마 판정), `DisclosureGradeServiceTest#상품_키_규칙은_계약_1_2_0과_같다`, `DisclosureGradeControllerTest#형식_오류_400`(+3건: 41자·보험사 9자·보험사 형식), `FlywayMigrationTest#스냅샷_번호_SEQUENCE와_소속_외부_키_폭`(H2), `OracleDdlFeaturesIT#스냅샷_채번은_SEQUENCE이고_소속_외부_키는_40자다`(Oracle — 40자 통과, 41자 ORA-12899) |
| 3 | GET 403 설명(인가 거부, 타 테넌트 = 404), 1.2.0 오류 코드 | `ed00f37` | `EngineDisclosureContractTest#계약_1_2_0의_오류_코드와_GET_403_설명`. 구현은 이미 계약과 같았다(GET 403 = 보안 체인 `FORBIDDEN`, 타 테넌트 = `SnapshotNotFound` 404 — `SnapshotRefetchIT#없는_스냅샷과_다른_테넌트는_404_원문_변조는_무결성_오류`). 주석·설계서만 갱신 |
| 4 | 채번 = Oracle SEQUENCE 하나(`DISC_GRADE_SNAPSHOT_NO`, V13), 일자 카운터·잠금·MERGE·재시도 제거, 번호 일자 재시작 없음 | `c0142e0` | `SnapshotNumberingConcurrencyIT#같은_순간_50건_발급은_실패_0_ID_유일`(Oracle, 처음 쓰는 날짜 2031-03-15에 CountDownLatch 50건 → 실패 0, ID 50개 유일, 형식 `GRD-20310315-\d{7}`), `DisclosureGradeServiceTest#채번은_날짜가_바뀌어도_이어진다`, `OracleDdlFeaturesIT#…SEQUENCE…`(min 1000000·max 9999999·NOCYCLE, `DISC_GRADE_SNAPSHOT_SEQ` 없음) |
| 5 | `CLAUDE.md` 신설(저장소 루트 — `CONTRIBUTING.md`와 같은 위치) | `c2b717d` | 문서. 항목마다 출처(부록 B-n·CONTRIBUTING 절·빌드 스크립트·심사 회신) 표기, 새 규칙 없음 |
| 6 | JCS 상호 검증 | `1590b5e` | `JcsCrossCheckTest` 1,051건: 파일 벡터 8(바이트)·공개 UTF-8 hex 7·§3.2.3 정렬 1·부록 B 26(+행 수 1)·ES6 표본 1,000(+행 수 1)·MUST 거부 6·서로게이트 쌍 1. 벡터 34개 파일은 ga-disclosure@9379be96 `platform-canonical/src/test/resources/jcs/`의 복사본 |
| 7 | `TEMP_PRODUCT` 제거(원인 enum·정책 픽스처), §5-8 "데이터 목록 호환" 폐기 | `979ed9c` | `GradingPolicyValidationTest#…`(broken "임시등록 원인 없음" — 정책에 `TEMP_PRODUCT`가 있으면 `unknown cause TEMP_PRODUCT`로 INVALID_POLICY) |
| 8 | (선택) CI `no-docker` 잡 — **했다** | `f294628`, `29704ce` | CI 잡 `no-docker`: 호스트 러너에서 데몬 정지·소켓 제거 후 `:commission-infra:integrationTest`가 실패(종료 코드 ≠ 0, Testcontainers Docker 미발견 오류, 결과 XML ≥ 1, 스킵 0, 실패 스위트 ≥ 1)해야 성공 |
| §4-4 | 서비스 토큰 해시 **목록**(1~2개, 회전 겹침) | `6f331f5` | `ServiceTokenFilterTest#회전_겹침_기간에는_현재와_다음_토큰을_모두_인정한다`, `#해시_목록_설정은_1개_또는_2개만_받는다`(쉼표 구분 바인딩, 3개·중복·형식 위반 기동 실패), `BootSmokeIT`(단일 해시 설정 그대로 동작) |
| — | 설계서 v1.2.3(§6.7, 변경 이력), 설계고찰 §15 | `41e4b7b` | — |

## 2. 주입 기록

| # | 주입 | 결과 |
|---|---|---|
| J1 | 항목 4 테스트(`SnapshotNumberingConcurrencyIT`)를 **수정 전 코드**(일자 카운터 `SELECT … FOR UPDATE` + 첫 행 INSERT)에 먼저 실행 | **실패**: 50건 중 7건 `DuplicateKeyException` — `ORA-00001: unique constraint (TEST.PK_DISC_SNAPSHOT_SEQ) violated … (SEQ_DATE:15-MAR-31)`. E3 §5-15의 경합을 재현한 뒤 SEQUENCE로 바꿔 통과 |
| J2 | `JcsCrossCheckTest`를 **수정 전 엔진 JCS**(erdtman `JsonCanonicalizer` 직접 호출)에 실행 | **실패 3건**: `lone-surrogate-high`·`lone-surrogate-in-key`·`lone-surrogate-low`가 예외 없이 정규화됨(나머지 1,048건 통과 — 중복 키·±Infinity는 라이브러리가 이미 거부). `Jcs.canonicalize`에 짝 없는 서로게이트 검사를 넣어 통과 |

## 3. 테스트 건수 (로컬, `./gradlew clean build --rerun-tasks --no-build-cache`, 결과 XML 직접 합산)

BUILD SUCCESSFUL, **10,188건, 실패 0, 스킵 0** (결과 XML 전부 이 빌드에서 생성 — 수정 시각 20:12:46~20:13:02).

| 모듈 | 건수 | E3 대비 |
|---|---|---|
| commission-domain | 5,060 | 0 |
| commission-limit | 3,021 | 0 |
| commission-disclosure | 1,641 | +1,069 (JCS 1,051, 계약 +15, 서비스 +2, 정책 검증 +1) |
| commission-infra integrationTest (Oracle) | 119 | +2 |
| commission-deferral | 111 | 0 |
| commission-rule | 66 | 0 |
| commission-settlement | 52 | 0 |
| commission-api | 44 | +3 |
| commission-calc | 32 | 0 |
| commission-clawback | 12 | 0 |
| commission-app | 9 | +2 |
| commission-inbound | 7 | 0 |
| commission-batch | 6 | 0 |
| commission-infra test (H2) | 3 | +1 |
| commission-shadow | 3 | 0 |
| commission-recon | 2 | 0 |

빌드 로그(242행)에서 지시문 형태의 문장은 0건이다.

## 4. CI

PR #2. `gh run view`로 직접 조회했다.

| 실행 | 이벤트 | 커밋 | 빠른 티어 | 풀 티어(Oracle IT + 기동 스모크) | no-docker |
|---|---|---|---|---|---|
| 36707297395 | pull_request | `74fc360` | success | success | **failure** — 잡의 판정 스크립트 결함(아래) |
| 36707293325 | push | `74fc360` | success | skipped(push는 대상 아님) | failure(같은 원인) |
| **36707595190** | pull_request | `29704ce` | **success** | **success**(BUILD SUCCESSFUL 1m 55s) | **success** |
| 36707588461 | push | `29704ce` | success | skipped | success |

- **no-docker 첫 실행의 실패 원인**: 통합 테스트는 의도대로 전부 실패했다(`113 tests completed, 113 failed`, Gradle 종료 코드 1). 그런데 잡이 Testcontainers의 `Could not find a valid Docker environment`를 **콘솔 로그**에서 찾았고, 엔진 빌드는 짧은 예외 형식이라 원인 메시지가 콘솔에 나오지 않는다. 결과 XML의 전체 스택에서 찾도록 고쳤다(`29704ce`). 두 번째 실행: `gradle exit code: 1`, `113 tests completed, 113 failed`, `result files: 27, with failures/errors: 27, skipped: 0`, `OK: integration tests failed (not skipped) because Docker is missing`. 113 = 119 − 7 + 1(`SnapshotImmutabilityIT`는 클래스 초기화 실패 1건으로 집계).
- 이 보고서 커밋 뒤의 head CI는 아래 추기에 적는다.

## 5. 설계서와 달리 구현한 지점·판단

1. **채번 대역 1000000~9999999(7자리)**: SEQUENCE를 1부터 시작하면 V12 일자 카운터가 이미 발급한 6자리 번호와 같은 날 충돌할 수 있다. 데이터 의존 DDL 대신 겹칠 수 없는 전용 대역을 택했다(`INCENTIVE_ADMIN_SEQ`와 같은 방식). ID는 항상 20자(`VARCHAR2(20)` 유지). NOCYCLE — 소진 시 실패(B-13). 계약은 `snapshotId`를 `string`으로만 정하고 ga-disclosure `SnapshotId`는 불투명 코드(≤ 64자)라 소비자 영향 없음.
2. **토큰 해시 목록 상한 2**: 심사 결정의 "현재·다음 두 개"를 기동 검증으로 강제했다(3개 이상·중복·형식 위반은 기동 실패). 설정 키 이름은 그대로(`app.internal.service-token-sha256`, 쉼표 구분) — 단일 값 설정은 그대로 동작한다.
3. **`UnavailableCause.TEMP_PRODUCT` 삭제**: 데이터만 빼지 않고 원인 어휘에서도 뺐다. 남겨 두면 정책에 `TEMP_PRODUCT` 매핑이 있어도 통과해 "호환" 경로가 살아 있기 때문이다. 이제 그런 정책은 `INVALID_POLICY`다.
4. **JCS 진입점 `Jcs`**: 라이브러리 직접 호출을 한 곳으로 모으고 짝 없는 서로게이트 거부를 더했다(J2). 스냅샷 응답은 엔진이 만든 `ObjectNode`라 실제 경로에서 서로게이트 단독 문자가 나올 일은 없지만, 두 저장소의 거부 집합까지 맞췄다.
5. **CLAUDE.md 위치**: 저장소 루트(`CONTRIBUTING.md`와 같은 곳). 설계서 부록 B·CONTRIBUTING에 없는 규칙은 넣지 않았고, 계약 복사 규칙은 루트 `build.gradle.kts` 주석과 심사 회신 §4-6에서 가져왔다.
6. **Flyway 버전 순서**: V13·V14(공통)는 이미 V103(Oracle 전용)까지 적용된 DB에서는 "낮은 버전 미적용"이 된다. E3의 V12도 같은 조건이었고 운영 DB가 아직 없으므로(가동 전) 그대로 두었다. 운영 가동 뒤의 공통 마이그레이션 번호 규칙은 질문 1.

## 6. 질문

1. **마이그레이션 번호 규칙**: 공통(`db/migration`)과 Oracle 전용(`db/vendor/oracle`, V100~)이 한 번호 공간을 공유해, 공통에 새 번호(V13·V14)를 더하면 V103이 적용된 DB에서 순서가 뒤집힌다. 가동 전에 (a) 공통도 V104 이후로 번호를 이어 가거나 (b) `outOfOrder`를 허용할지 정해야 한다. 권장 (a) — 다음 마이그레이션부터 공통·전용 구분 없이 단조 증가.
2. **스냅샷 항목 키 폭**: `DISC_GRADE_SNAPSHOT_ITEM.product_key`는 VARCHAR2(129) 그대로다(심사가 소속 컬럼만 지정). 요청 검증이 40자를 넘는 키를 막으므로 실해는 없지만 폭을 맞출지(불변 테이블이라 ALTER만) 결정이 필요하다.
