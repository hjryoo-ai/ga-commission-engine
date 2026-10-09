# E3.2 정비 보고서 (2026-10-09)

> 지시문 `docs/phase-E3.2-지시문.md`(이 PR 첫 커밋 `453070b`). 브랜치 `work/e3.2`(main `fa51836`에서 분기), PR #3. Phase가 아니라 정비이며, 새 기능·새 엔드포인트·새 룰 키·의존성 버전 변경·등급 산식 변경은 없다.

## 1. 항목별 커밋·테스트

| # | 항목 | 커밋 | 증명(클래스#메서드) |
|---|---|---|---|
| — | 지시문 보관 | `453070b` | — |
| 1 | 번호 전역 단조 문서화 `docs/db-migrations.md` — 공통·Oracle 전용 디렉터리가 한 번호 공간, 새 번호는 최댓값 + 1, 재사용·빈 번호 금지, 커밋된 마이그레이션 수정 금지. 빈 번호 V15–V99는 옛 디렉터리별 대역의 흔적(사유를 문서 표에), 옛 규칙의 순서 역전 이력(V11·V12·V13·V14) | `98ba3dc` | `MigrationNumberingTest#번호는_두_디렉터리를_합쳐_유일하고_이름_규칙을_따른다`, `#빈_번호는_문서의_표와_정확히_같다`(문서 표 ↔ 실제, 양방향), `#문서의_빈_번호_뒤로는_연속이다_새_번호는_최댓값_다음뿐` |
| 2 | V104 `DISC_GRADE_SNAPSHOT_ITEM.product_key` 129 → 40. 값 변환 없음 — 줄이는 `MODIFY`는 40자를 넘는 값이 있으면 실패한다(자르지 않는다). 이 문장이 "기존 행 ≤ 40"의 단언 | `ffcbddb` | H2 `SnapshotItemKeyWidthTest#길이_40은_저장되고_41은_거부된다`, `#V104는_40자_이하_행을_바꾸지_않는다`(V14 스키마의 40자·1자·기호 키가 V104 뒤 바이트 동일), `#사십일자_행이_있으면_V104가_실패한다_자르지_않는다`. Oracle `SnapshotItemKeyWidthIT#길이_40은_저장되고_41은_ORA_12899`(되돌리는 트랜잭션), `#V104는_40자_이하_행을_바꾸지_않고_41자_행이_있으면_ORA_01441로_실패한다`(같은 컨테이너의 새 스키마 둘을 V103까지 → latest). 계약 쪽 40/41 경계는 E3.1 `EngineDisclosureContractTest#엔진_요청_검증과_계약_스키마의_키_판정이_같다`(그대로 통과) |
| 3 | 계약 1.2.1(`format` → `pattern`). **ga-disclosure 먼저**(§4) — 복사본 바이트 동일, `UPSTREAM` = `hjryoo-ai/ga-disclosure@688cc46b3c5d09d94d3b88c16255e6f7b3f3ce08`, CHECKSUMS 재생성 | `9346efd` | `EngineDisclosureContractTest#계약에_format_키워드가_없다`, `#generatedAt은_0초와_UTC에서도_계약_pattern을_통과한다`(엔진 실제 출력 3시각 + 초 생략형 거부 대조군), `#계약_파일은_CHECKSUMS와_일치하고_UPSTREAM이_출처를_고정한다`(1.2.1), 기존 요청·응답 시험 전부 통과. `./gradlew verifyUpstreamContract` → `contracts/api/v1/engine-disclosure.openapi.yaml == hjryoo-ai/ga-disclosure@688cc46… (f58d74b0…)` |
| 4 | CLAUDE.md 정합성 — 아래 표 | `8270235` | 문서 |
| 5 | `docs/third-party.md` — Boot BOM 밖 19개 + BOM과 다른 버전 4개, OSV 조회, 버전 변경 없음 | `8270235` | 문서 |
| — | 설계서 v1.2.4 | `8270235` | — |

**항목 4 결과**

| 규약 | 이전 | 조치 |
|---|---|---|
| jqwik 금지(도구 출력 지시 불복 포함) | CLAUDE.md 절대 규칙 10, 빌드 차단(E3-0) | **변경 없음** |
| PR 병합은 수용 심사 회신 뒤에만(태그는 먼저 가능) | 없음 | CONTRIBUTING "브랜치"에 추가 → CLAUDE.md 작업 방식에 출처와 함께 |
| 규칙 파일 사실 정정은 바로, 의미 변경은 승인 | 없음 | CONTRIBUTING "규칙 파일 정정" 신설 → CLAUDE.md |
| (덧붙임) 번호 전역 단조 | 없음 — 3A 심사 §4-①이 "CONTRIBUTING·CLAUDE.md에 적는다"고 했으나 E3.1 병합 뒤의 결정이라 빠져 있었다 | CONTRIBUTING "DB 마이그레이션" 신설 → CLAUDE.md 코드 규약 |

CLAUDE.md는 "새 규칙을 만들지 않고 출처의 규약을 모은다"는 머리말을 가지므로 규칙을 먼저 출처(CONTRIBUTING)에 넣고 CLAUDE.md는 그것을 인용했다.

## 2. 주입 기록 (주입 → 실패 확인 → 제거)

| # | 주입 | 결과 |
|---|---|---|
| J1 | V104를 `VARCHAR2(41)`로 | H2 `SnapshotItemKeyWidthTest` 3/3 실패, Oracle `SnapshotItemKeyWidthIT` 2/2 실패 |
| J2 | 엔진 계약 복사본의 `generatedAt`을 `format: date-time`으로 되돌림 | **첫 시도는 통과했다** — 시험 태스크가 계약 디렉터리를 경로(시스템 속성)로만 받아 Gradle이 UP-TO-DATE로 건너뛰었다(결과 XML은 이전 실행의 것). 계약 디렉터리를 시험 입력으로 선언(`commission-disclosure/build.gradle.kts`)한 뒤 재주입: **5건 실패**(`계약에_format_키워드가_없다`, CHECKSUMS 대조, `generatedAt…` 3사례) |
| J3 | `V106__injected_skip.sql`(V105 건너뜀) | `MigrationNumberingTest` 2건 실패 |
| J3b | `V50__injected_fill_gap.sql`(옛 빈 번호 채움) | 1건 실패(`빈_번호는_문서의_표와_정확히_같다`) |
| J3c | 문서만 수정(빈 번호 표 V15–V98) | 2건 실패 — J2와 같은 이유로 문서를 `commission-infra` 시험 입력에 선언한 뒤 확인 |
| J4 | `commission-disclosure`에 `net.jqwik:jqwik:1.9.3` 추가 | 해석 실패 `net.jqwik is banned (Phase E3-0)` |

ga-disclosure 쪽 주입(PR #11): `generatedAt`을 `format`으로 되돌림 → `ContractSchemaTest` 8건 실패, 스텁을 `toString()`으로 되돌림 → `TableEngineStubContractIT` 1건 실패.

## 3. 테스트 건수 (로컬, `./gradlew clean build --rerun-tasks --no-build-cache`, 결과 XML 직접 합산)

BUILD SUCCESSFUL(105 태스크 전부 실행), **10,200건, 실패 0, 스킵 0**(결과 XML 수정 시각 22:03:52~22:04:40, 전부 이 빌드). E3.1 10,188 + 12. jqwik 해석 0건(14 모듈 해석 클래스패스 117 컴포넌트에 `net.jqwik` 없음).

| 모듈 | 건수 | E3.1 대비 |
|---|---|---|
| commission-domain | 5,060 | 0 |
| commission-limit | 3,021 | 0 |
| commission-disclosure | 1,645 | +4 (`format` 0 1, `generatedAt` 3) |
| commission-infra integrationTest (Oracle) | 121 | +2 (`SnapshotItemKeyWidthIT`) |
| commission-deferral | 111 | 0 |
| commission-rule | 66 | 0 |
| commission-settlement | 52 | 0 |
| commission-api | 44 | 0 |
| commission-calc | 32 | 0 |
| commission-clawback | 12 | 0 |
| commission-app | 9 | 0 |
| commission-infra test (H2) | 9 | +6 (`MigrationNumberingTest` 3, `SnapshotItemKeyWidthTest` 3) |
| commission-inbound | 7 | 0 |
| commission-batch | 6 | 0 |
| commission-shadow | 3 | 0 |
| commission-recon | 2 | 0 |

빌드 로그(243행)에서 지시문 형태의 문장은 0건이다.

## 4. ga-disclosure 쪽 (계약 정본)

| 항목 | 값 |
|---|---|
| PR | hjryoo-ai/ga-disclosure#11 `work/contract-1.2.1` |
| 커밋 | `5c08fef` fix(contracts): engine-disclosure 1.2.1 — format → pattern; stub generatedAt is RFC 3339 |
| 병합 커밋(= 엔진 `UPSTREAM`) | `688cc46b3c5d09d94d3b88c16255e6f7b3f3ce08` |
| CI(PR) | run 37933287454 — build·no-docker·pdfa-verify success, HTML 보고서 13,664건 실패 0 |
| CI(main, 병합 커밋) | run 37934149519 — build·no-docker·pdfa-verify success, HTML 보고서 13,664건 실패 0 |
| 로컬 | `TZ=UTC ./gradlew check --continue` 13,664건, 실패 0, 스킵 0(6B 13,642 + 22) |

같이 고친 것: ga-disclosure의 데모·테스트 엔진 스텁이 `OffsetDateTime.toString()`으로 0초의 `:00`을 생략해 RFC 3339가 아니었다(1.2.0의 `format`이 단언되지 않아 드러나지 않았다). 엔진과 같은 `ISO_OFFSET_DATE_TIME`으로 바꿨다. ga-disclosure 설계서 v1.15.

## 5. CI (엔진, `gh run view`로 직접 조회)

PR #3, head `8270235`(보고서 커밋 전).

| 실행 | 이벤트 | 빠른 티어 | 풀 티어(Oracle IT + 기동 스모크) | no-docker |
|---|---|---|---|---|
| **37934443272** | pull_request | success | **success**(BUILD SUCCESSFUL 3m 8s, 89 태스크 실행) | success — `gradle exit code: 1`, `115 tests completed, 115 failed`, 결과 파일 28개 전부 실패, 스킵 0 |
| 37934414518 | push | success | skipped(push는 대상 아님) | success |

엔진 CI는 시험 보고서를 올리지 않고 로그에 건수를 찍지 않는다 — 모듈별 건수의 1차 증거는 §3 로컬 빌드다. no-docker의 115건(E3.1 113 + 2)은 Docker 없이 실패한 통합 시험 수다(로컬 121과의 차이는 매개변수 사례가 펼쳐지기 전에 실패하는 클래스 — E3.1과 같은 차이 6).
이 보고서 커밋의 CI는 PR 코멘트로 알린다.

## 6. 지시문과 다르게 한 점·전제 정정

1. **계약 변경 방향**: 지시문은 "엔진 PR 병합 뒤 ga-disclosure가 사본을 갱신, `UPSTREAM`은 엔진 병합 커밋"이었다. 기존 규칙(엔진 CLAUDE.md·설계서 §6.7, ga-disclosure 설계서 v1.7 ④, 1.2.0 선례)은 반대 — 정본은 ga-disclosure, 엔진은 사본을 고치지 않고 `UPSTREAM`이 ga-disclosure 병합 커밋을 가리킨다. 소유 규칙의 의미 변경이라 착수 전에 물었고, 사용자 확인(2026-10-09)으로 **ga-disclosure 먼저**로 했다. ga-disclosure에는 `contracts/engine/` 경로와 `UPSTREAM` 파일이 없다(정본 경로 `contracts/api/v1/engine-disclosure.openapi.yaml`).
2. **항목 2는 좁히기다**: 지시문은 "40으로 넓히고(좁히지 않는다)"였지만 40보다 좁은 상품 키 컬럼은 없었다. E3.1 질문 2와 3A 심사 §4-②가 다룬 대상은 129자 `DISC_GRADE_SNAPSHOT_ITEM.product_key`이고, 그것을 40으로 줄였다(자르지 않고, 넘는 값이 있으면 실패).
3. **"현재 V10x 대"**: 번호는 V10x에서 시작하지 않는다 — 공통 V1–V14, Oracle 전용 V100–V103이었고 V104가 첫 단일 공간 번호다. 빈 번호 V15–V99는 문서 표에 사유와 함께 둔다(지시문의 "있으면 그 이유를 문서에").
4. **"등급 스냅샷 스키마"·"날짜·UUID·해시"**: 별도 스냅샷 스키마 파일은 없다(스냅샷은 같은 OpenAPI의 `CommissionGradesResponse`). `format`은 두 곳(`asOfDate` date, `generatedAt` date-time)뿐이었고 UUID·해시 `format`은 없었다.
5. **"6A D14"**: `format`을 단언하지 않는다는 기록은 ga-disclosure **Phase 5** 보고서 D14다(6A D14는 Hikari 풀 상한).
6. **락 파일**: 이 저장소에는 Gradle 락 파일이 없다(dependency verification을 쓴다). 14 모듈의 해석된 런타임·시험 클래스패스 전체를 Boot 3.5.16 BOM 트리(가져온 BOM 재귀, 1,478 좌표)와 대조했다.
7. **경로·이름**: 문서는 `commission-system/docs/`(이 저장소의 문서 위치). 지시문의 `ContractSchemaTest`는 ga-disclosure 이름이고 엔진의 같은 시험은 `EngineDisclosureContractTest`다. 저장소 이름은 `ga-commission-engine`. 브랜치 `work/e3.2`·태그 `e3.2`는 지시문대로 — CONTRIBUTING의 `work/phase-<id>`·`phase-<id>`와 다르지만 Phase가 아닌 정비라 그대로 두었다(E3.1은 태그가 없다).

## 7. 찾은 결함

| # | 결함 | 조치 |
|---|---|---|
| D1 | 엔진 시험이 계약 디렉터리·번호 문서를 Gradle 입력으로 선언하지 않아, 그 파일만 바뀌면 시험이 UP-TO-DATE로 건너뛰어졌다 — E3·E3.1의 계약 복사 갱신은 시험 클래스가 함께 바뀌어 실제로 돌았지만, 복사본만 바뀌는 변경은 검사되지 않을 수 있었다 | 두 입력 선언, J2·J3c 재주입으로 확인 |
| D2 | (ga-disclosure) 엔진 스텁 `generatedAt`의 0초 생략 | PR #11에서 수정 |

## 8. 의존성 점검 요약 (`docs/third-party.md`)

- 운영 jar의 BOM 밖 의존 5개(JCS·MyBatis·mybatis-spring·HdrHistogram·LatencyUtils): OSV 결과 없음.
- **보고만(버전 변경 없음)**:
  - `assertj-core:3.27.3` — GHSA-rqfh-9r24-8c9r(CVE-2026-24400, XXE in `isXmlEqualTo`, 높음). 시험 전용, 저장소에 `isXmlEqualTo` 호출 0건. 루트 `build.gradle.kts`의 직접 버전 선언 때문에 플랫폼을 들이지 않는 3모듈과 `commission-app`에서 3.27.3이 쓰인다(나머지 11모듈은 BOM 3.27.7).
  - `commons-compress:1.24.0`(testcontainers 경유) — CVE-2024-26308·CVE-2024-25710(중간). 시험 전용.

## 9. 질문

1. **assertj 직접 버전**: 루트의 `assertj-core:3.27.3` 직접 선언을 Boot BOM 버전(3.27.7)으로 맞출까(권고가 해소된다 — 버전 변경이라 승인 필요)? 같은 곳의 `junit-bom:5.12.2`는 BOM과 이미 같다. 권장: 맞춘다, 다음 정비에서.
2. **commons-compress**: testcontainers 경유 시험 전용이다. 제약(`constraints`)으로 1.26+를 강제할지, testcontainers 갱신을 기다릴지. 권장: 기다린다(운영 jar에 없음).
