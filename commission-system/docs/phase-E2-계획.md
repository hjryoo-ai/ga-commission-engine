# E2 계획 — 잠정 명세·시뮬레이션·명세 조회 (지시문 `docs/phase-E2-지시문.md` v1.0)

> 브랜치 `work/e2`(`main` = E1 병합 `aef2730` + 지시문 보관). **승인 뒤 1단계(⓪).** 포털 계약 v1.2는 병합됐다 — 포털 PR #5 병합 커밋
> **`3f876b67a6991351f30b6276991c457a043572cf`**. 이것이 E2의 `contracts/shared/ga-agent-portal/UPSTREAM`이다(⓪b에서 올린다).
>
> 지시문 §0 어휘 확인(멈출 일인지): v1.2 `DeferralStatusChanged.reason` = 엔진 `DeferralTransitionReason`(`DUE_RELEASED`·`POLICY_NOT_INFORCE`·
> `POLICY_TERMINATED`·`SOURCE_REVERSED`), `ClawbackReceivableUpdated.status` = 엔진 `ReceivableStatus`(`OPEN`·`OFFSET`·`CLOSED`·`WRITTEN_OFF`) —
> **글자까지 같다. 멈추지 않는다.** 채권 `amount`·`remaining`은 엔진에서 원래 0 이상이다(`ClawbackReceivable.rehydrate` — 잔액 음수·원금 초과면 예외).
> 이 일치를 시험으로 고정한다(§3 `PortalVocabularyTest`, 주입 S16).

이번 계획을 위해 엔진을 다시 읽었다(2026-10-17, `aef2730`). **지시문 문장 가운데 엔진 코드와 다르거나 그대로는 성립하지 않는 것이 17개 있다(§0).**
그중 계약을 바꿔야 풀리는 것(P14~P16, ②의 응답·요청 모양)은 포털 계약 요청 목록(§4)으로 올리고, ②는 그 포털 PR이 병합된 뒤 착수한다.
나머지는 권장안으로 진행하고 보고서에 적는다.

## 0. 지시문 문장 ↔ 엔진 사실

| # | 지시문 | 엔진 사실(파일:행) | 판정·권장 |
|---|---|---|---|
| **P1** | ① "그 달 열린 레코드의 순액…", C2 "①을 낸 뒤 새 이벤트 없이 마감·지급하면 예상 = 실제" | 마감이 레코드를 **더 만든다**: `MonthCloseService.close` ②가 훅을 돌리고(`MonthCloseService:81-88`), 운영 훅 `DeferralReleaseCloseHook`가 도래분(`due_ym = 그 달`, `status = SCHEDULED` — `DeferralScheduleMapper:41-43`)을 `DEFERRED` 레코드로 그 달에 넣는다(`DeferralReleaseService:42-63`). 열린 레코드만 보면 분급이 도래하는 달마다 C2가 깨진다 | 예상은 **도래 미리보기**를 포함한다: 같은 `findDue(ym)` + 같은 `PolicyStatusProvider`로 유지면 `entry.amount()`의 DEFERRED 한 줄, 미유지면 0(HELD). 이미 `deferralReleaseJob`이 돌았으면 `SCHEDULED`가 아니라서 미리보기에 없고 레코드로 이미 있다 — 이중 계상 0. 주입 S8 |
| **P2** | ① "채권 상계 예상" | `ClawbackOffsetService.consume`(`:44-63`)은 상태를 바꾼다 — `receivable.offset`(도메인 변경) → `store.save` → `recordOffset`. 쓰지 않는 상계 계산이 없다 | 읽기 전용 미리보기 `previewConsume(agentId, available)` = Σ(`findOffsettable` 순서대로 `min(remaining, left)`) — `consume`과 **같은 반복 함수**를 쓰게 뽑는다(쓰는 쪽은 그 결과를 저장, 미리보기는 버린다). `findOffsettable`에 `FOR UPDATE` 없음(E1 F8, `ClawbackReceivableMapper:31-37`) — C3에 맞다. 주입 S9 |
| **P3** | ② "원장은 읽기 스냅샷(락 없음)", 그리고 기존 `CommissionCalculator.dryRun`("저장 없이 파이프라인만 실행 — 시뮬레이션·replay 용", `CommissionCalculator:89-99`) | **기존 dryRun은 쓰기·잠금이 있다.** `LimitGateStep:71`의 `getOrCreate` = Oracle `lockByKey`(`SELECT … FOR UPDATE`, `LimitLedgerMapper:47`) + 없으면 `INSERT`(`OracleLimitLedgerStore:44-77`), `PremiumRepriceStep:55-67`은 `find`(잠금) + `save`. 운영 조립의 저장소는 E1 발행 데코레이터라 `getOrCreate`가 **`LimitLedgerOpened`를 적재**한다(`PersistenceConfig:73-92`). "저장 없이"는 리스너를 안 부른다는 뜻일 뿐이다 | dryRun을 운영 저장소로 돌리지 않는다. **같은 Step 클래스·같은 순서**의 두 번째 파이프라인을 **오버레이 저장소**(읽기는 잠금 없는 조회, 쓰기는 메모리에만)로 조립한다(§2-②). 데코레이터는 구조적으로 닿지 않는다(오버레이는 `PublishingStores`가 아니라 원 저장소의 읽기 포트만 감싼다). 주입 S6·S7·S12 |
| **P4** | ② "파이프라인·환수 계산을 영속화 없이 실행" | 엔진에 이미 `SimulationService`(`commission-api`, `/api/commissions/simulate`)가 있으나 **파이프라인이 아니다** — 요율 × 지급률을 따로 다시 계산하고(`SimulationService:140-151`), V2 시책·기존 원장 누적·환수·분급 커브 Step을 거치지 않는다. 계약 §4.3 모양도 아니다 | 새 시뮬레이터는 파이프라인 경유(P3). 기존 `/api/commissions/simulate`는 **건드리지 않는다**(범위 밖) — 두 계산이 어긋날 수 있다는 사실을 §11 미결정과 보고서에(나중에 새 시뮬레이터로 대체 후보) |
| **P5** | ① "주기는 배치 파라미터(기본 야간 1회)" | 엔진에 스케줄러가 없다(E1 P8 — 잡은 `POST /api/batch/{job}/run`, `BatchLaunchController:25-53`). E1 승인이 "스케줄러를 새로 들이지 않는다"로 정했다 | 잡 `statementEstimateJob`(파라미터 `close_ym` 선택 — 없으면 오늘 날짜의 귀속월) + 운영 cron(야간 1회)을 README·설계서에. 온디맨드(③ `ESTIMATE`)는 같은 `StatementEstimator` |
| **P6** | ⓪ "두 번째 판 — 첫 판의 이벤트는 **바이트 그대로** 포함(추가만)" | `seq`·순차 `eventId`(`OutboxEngine.sequentialIds`, `UUID(0, n)`)는 **위치값**이다. `StatementEstimated`를 202608 마감 **앞**(포털 A-1 seq 7 자리)에 넣으면 그 뒤 모든 이벤트의 `seq`·`eventId`가 1씩 밀려 첫 판이 부분집합이 아니게 된다 | 두 번째 판의 `StatementEstimated`는 **첫 판 마지막 이벤트 뒤**에만 온다 — 해약 뒤 야간 잠정 명세(202702, 순액 음수: `gross 0`·`clawbackOffset −720,000`·세액 0·`net 0`). 그러면 C9 "첫 판 ⊂ 두 번째 판(바이트)"이 성립한다. 대안(마감 앞 202608 잠정 명세)은 Q8 |
| **P7** | ⑤ "Oracle Free 컨테이너(digest 고정)" | 기존 IT는 **태그만** 쓴다 — `gvenzl/oracle-free:23-slim-faststart`(`OracleTestSupport:38`, `BootSmokeIT:53`), digest 없음 | 이미지 참조를 한 곳(버전 카탈로그 `gradle/libs.versions.toml`의 `oracle-free-image = "…@sha256:…"`)에 두고 compose·두 IT가 같은 값을 읽는다(digest는 구현 시점에 `docker buildx imagetools inspect`로 확인해 고정). 시험 입력 선언(`verifyTestInputs`) |
| **P8** | ⑤ "시드 + 엔진 앱 기동 → `GET /internal/v1/events?afterSeq=0`이 A-1 스트림", C8 "두 번째 판과 **바이트 동일**(`seq` 포함)" | ① 엔진에는 정책 이벤트를 받는 운영 경로가 없다(E1 P12 — `process()`는 시험·`recalcJob`의 `rebook`만). ② 운영 발행기는 **무작위 UUID + 시스템 시계**(`PersistenceConfig:64-67`, `OutboxPublisher:31-33`) — 같은 바이트가 나올 수 없다. ③ 시드: `RuleFixtures.standardRules()`는 **인메모리 픽스처**이고 Oracle용 시드 SQL은 없다(Oracle IT는 시험마다 직접 넣는다) | 앱 jar 밖의 **시나리오 실행기 모듈** `commission-localstack`(main 클래스 하나): 앱 설정을 그대로 쓰되 `Clock`을 **단계 시계**, 발행기 ID를 순차로 바꿔 끼우고 A-1 입력을 계산 → 마감 → 지급 → 해약 → 잠정 명세 순으로 돌린 뒤 끝난다. 그다음 보통 앱이 떠서 피드를 낸다(부여는 lazy — `event_no` 순이라 결정적). 시드 SQL `localstack/seed/a1.sql`은 `standardRules`와 같은 값 — **C8이 그 둘의 일치를 증명**한다(하나라도 다르면 바이트가 다르다, 주입 S11). 단계 시계·순차 ID는 이 모듈에만 있다(운영 jar에 없음) |
| **P9** | §4 "룰 데이터 하드코딩(세율) 금지" | 원천세율은 **이미 코드 상수**다 — `WithholdingTaxPolicy.STANDARD_3_3`(`WithholdingTaxPolicy:17-18`)을 `ServiceConfig:123`이 `PayoutService`에 직접 넘긴다 | E2는 새 상수를 만들지 않는다: `WithholdingTaxPolicy`를 `@Bean` 하나로 뽑아 `PayoutService`와 `StatementEstimator`가 **같은 인스턴스**를 받는다(예상 = 실제의 전제, 주입 S13). 세율을 룰 데이터로 옮기는 것은 E2 밖 — 엔진 §11 미결정(E1 이전부터의 사실) |
| **P10** | ④ "해석기를 거치는 기존 경로의 동작 변경 0" | 바뀌는 경로가 **하나** 있다: `deferralReleaseJob`(`DeferralReleaseJobFactory:36`)에 이미 **CLOSED**인 `close_ym`을 주면 `DeferralReleaseService:52`가 그 마감월로 `DEFERRED`를 넣는다(해석기를 거치지 않는 insert — 마감월 불변 위반). 가드를 넣으면 이 잡이 실패·롤백한다 | 동작 변경이지만 결함 수정이다. 실제로는 마감 훅이 도래분을 이미 다 풀었으므로(남는 것은 HELD) 닿기 어렵다. 보고서·설계서 §6.4에 "가드가 막는 유일한 기존 경로". 골든 24·E1 골든 5는 동일(§3 C7 — 골든 조립에도 가드를 넣어 증명) |
| **P11** | ③ "`CONFIRMED`는 마감·지급 사실(`StatementConfirmed`+`PayoutCompleted`와 같은 값)" | 계약 응답은 `oneOf(StatementEstimated, StatementConfirmed)` payload 모양이다(`engine-internal.openapi.yaml` `getStatement`). `PayoutCompleted`의 `runSeq`·`receivableOffset`·`carriedReceivable`·`payable`은 `StatementConfirmed`에 없다 — 들어갈 자리는 선택 필드 `withholdingTax`·`net`뿐 | `CONFIRMED` = `StatementConfirmed` payload + 지급 뒤면 `withholdingTax`·`net`(그 달 지급 런 전부의 합 — `PAYOUT_STATEMENT`). 지급 전이면 둘 다 없음. C6은 "공통 필드 단위 동일"로 증명. 지급 확장 필드를 응답에 실으려면 계약 요청(§4 R8) |
| **P12** | C4 "같은 입력 2회 → **같은 응답 바이트**" | `NewContractResult.generatedAt`이 필수 instant다 — 실제 시계로는 두 응답의 바이트가 다르다 | 시험은 고정 `Clock`으로 바이트 동일, 운영 의미는 "`generatedAt`을 뺀 바이트 동일". `PolicyEventResult`에는 시각이 없어 그대로 바이트 동일 |
| **P13** | C1 "아웃박스는 ①의 `StatementEstimated` 행만 증가", §4 "아웃박스 UPDATE/DELETE 금지" | ①은 산출 전에 순번 부여를 돌린다(Q3) — 미부여 행의 `seq`가 NULL → 값(V106 트리거가 허용하는 단 하나의 UPDATE, E1 승인 방식) | §4는 "E1 1회 부여 외의 UPDATE 금지"로 읽는다. C1 시험은 시작 전에 부여를 한 번 끝낸 뒤 스냅샷을 떠서 "기존 행 전부 바이트 동일 + 새 행 = `StatementEstimated`만"을 단언한다 |
| **P14** | C5 "2026-06-30 이전 체결(한도 미적용)" | 계약 `NewContractResult.limitAmount`·`projectedAccum`이 **필수**(`nonNegativeWon`·`won`)다. 엔진은 한도룰이 없으면 원장 자체가 없다(`LimitGateStep:64-67`) — 0은 "한도 0"과 구별되지 않는다 | **계약 요청 R2**(두 필드 선택 — 부재 = 한도 미적용). 병합 전에는 이 케이스를 구현하지 않는다 |
| **P15** | ② new-contract 요청(`agentId`·`productKey`·`monthlyPremium`·`contractDate`) | 파이프라인 입력 `PolicyEvent`는 `insurerCd` 필수(`PolicyEvent` 생성자), 요율은 (보험사, 상품) 키(`RuleRepository.findRate`). 상품 마스터가 없어 `productKey`만으로 보험사를 정할 수 없다 | **계약 요청 R1**(`insurerCd` 선택 추가). 엔진은 없으면 기준일에 그 `productKey`의 유효 `COMM_RATE`가 한 보험사뿐일 때만 그것으로, 아니면 422 |
| **P16** | ② policy-event 요청(`agentId`·`policyNo`·`eventType`·`asOfDate`) | ① `REDUCE`는 새 보험료 속성이 있어야 재산정한다(`PremiumRepriceStep:41-43`) — 요청에 없다. ② `PAYMENT_OVERDUE`는 엔진에 처리가 없다(포털 K9 "우리 엔진이 발행하지 않는다"). ③ 경과 회차가 없으면 `ClawbackStep`이 **계약일~이벤트일 개월 수로 근사**한다 — "해약 회차 경계 케이스"(C5)는 근사로 하루 차이에 구간이 바뀐다 | **계약 요청 R4**(`newMonthlyPremium`·`elapsedInstallments` 선택) + R6(지원하지 않는 유형 422). 결과 필드의 뜻(`clawbackAmount` 부호, `offsetAgainstMonth.before/after`, `receivableRemaining`)이 계약에 없다 — R5 |
| **P17** | ⓪ "`PortalA1ComparisonTest`가 입력을 받아… 포털이 이것을 샘플 정본으로" | ① 엔진의 포털 시험들이 샘플 경로를 `events/v1/samples/<이름>`으로 읽는다(`PortalA1ComparisonTest:64`, `PortalContractAgreementTest:49`, `PortalContract:114-118`) — v1.2의 `a1/`·`contract-only/`·`standalone/` 분리로 깨진다. ② 포털이 엔진 스트림을 들이는 것은 포털 Phase 2(병합은 그 수용 뒤)라, E2 동안 엔진이 바라보는 포털 `a1/`(@`3f876b6`)은 **옛 값(월납 300,000)** 이다 | ⓪b(UPSTREAM 올림)에서 경로를 디렉터리 포함으로 고친다. A-1 대조는 E2 동안 **차이 표 + 자기 골든**(엔진 파일 바이트 고정)이고, 포털 Phase 2 병합 뒤 다음 `UPSTREAM` 올림에서 "포털 `a1/` 사본 = 엔진 첫 판(바이트)"으로 바뀐다(E1 수용 심사 §3-3의 바이트 대조 — 그때 가능). 순서 문제라 결함 아님 |

**A-1 입력값 확인**(포털 Phase 1 수용 심사 §3-3의 예상과 대조 — **같다**): 한도 = 150,000 × 12 = **1,800,000**(`LIMIT_RULE` 배수 12), FY = 150,000 × 7.0(`RuleFixtures:62`) × 0.9(SENIOR 지급률, `:72`) = **945,000**, 시책 1,000,000 → 가용 855,000 → **855,000 지급 + 145,000 삭감**(`LimitGateStep:92-95`, `DEFER_AFTER_FY`여도 삭감 — E1 P1). 이어지는 값(손으로 계산, ⓪ 시험이 확인): 202608 지급 `gross` 1,800,000·원천세 54,000 + 5,400 = 59,400·`net` 1,740,600, 2027-02-15 해약 7회차 환수 = 40% × 1,800,000(환수 대상 순 기지급 = 삭감 **뒤** `calc_amount` 합, `ClawbackStep.netClawbackablePaid`) = **−720,000**, 원장 누적 1,080,000. 한 가지 정정: "시책 1,000,000 정액"은 시드 룰이 아니라 **이벤트 속성**(`incentive_amount`, V1 `IncentiveStep`)이다 — 시나리오 입력에 넣는다.

## 1. 결정이 필요한 것 (지시문 §3 + 계획에서 생긴 것)

| # | 질문 | 권장 | 대안과 안 되는 이유 |
|---|---|---|---|
| Q1 | dry-run 구조, E1 데코레이터 끊기 | **①은 파이프라인·`PayoutService`를 돌리지 않는 순수 산출기**(`StatementEstimator` — 읽기 포트만 받는다, 컴파일 시점에 쓰기 불가) + `PayoutService`와 공유하는 순수 함수 `PayoutMath`(상계·지급가능액·세액). **②는 같은 Step 목록의 두 번째 파이프라인 + 오버레이 저장소**(P3). 데코레이터는 운영 저장소 빈에만 있고 오버레이는 원 저장소의 읽기만 감싸므로 발행 경로가 없다 | (a) 읽기 전용 트랜잭션 + 롤백: Oracle `READ ONLY` 트랜잭션은 `getOrCreate`의 INSERT에서 실패하고 `FOR UPDATE`는 여전히 잠근다(C3 실패). (b) 쓰기 트랜잭션 + 강제 롤백: 원장을 잠그고(C3 실패), 아웃박스 행·IDENTITY를 소비하며, 롤백을 빠뜨린 버그 하나가 실제 기록이 된다. (c) DB 전체 인메모리 복사: 비용 |
| Q2 | `ruleSnapshotId` | **`RS-` + SHA-256(사용된 룰 버전 참조 목록)의 앞 32자 16진**. 목록 = 시뮬레이션이 돌린 모든 컨텍스트의 `RuleSnapshot.usedRuleVersions()`(`type|key|ref` 줄)를 정렬·중복 제거해 UTF-8 `\n` 연결. 35자 ≤ 계약 `id` 64자. 범위 = **룰만**(같은 입력·같은 룰 버전 → 같은 값, 쓰인 룰의 버전 하나가 바뀌면 다른 값 — C4). 금액의 재현은 룰 + 원장·레코드 상태에 달렸다는 것을 설계서에 적는다 | 원장 상태까지 넣기: 그 설계사의 무관한 지급·전기마다 바뀌어 "룰 스냅샷"이 아니게 되고 포털 `simulation_log`의 비교 키로 못 쓴다. 시각 기반: C4 위반(주입 S2) |
| Q3 | `basisSeq`와 lazy 부여 | **잡은 산출 직전에 부여를 한 번 돌린다**(쓰기 트랜잭션, 0건까지 — `OutboxSequencer`), 그 뒤 `headSeq` = S를 읽고 산출은 별도 읽기 트랜잭션. `basisSeq = S`의 뜻은 **하한**: "seq ≤ S인 사실은 전부 반영, 그 뒤 커밋된 사실은 일부 반영될 수 있다"(부여와 산출 사이에 커밋된 업무는 보이지만 순번은 나중). ③ `ESTIMATE`는 부여하지 않고 현재 `headSeq`(읽기만) | 부여 없이 `headSeq`: 하한이 더 느슨할 뿐 같은 성질 — 잡에서는 부여가 싸니 붙인다. 정확한 절단: 업무 쓰기를 막아야 한다(불가). 계약 설명문에 하한의 뜻을 요청(R7) |
| Q4 | ③ `CONFIRMED`의 출처 | **업무 표 + 발행기와 같은 payload 빌더**: `COMM_CALC`(그 `close_ym` AGENT 레코드, 전 상태) + `ADJUSTMENT`(`findByCloseYm`) + `SETTLE_CLOSE.closed_at` + `PAYOUT_STATEMENT`(세액·순액). 빌더는 `PortalPayloads`의 `StatementConfirmed` 함수를 그대로 부른다 → 같은 입력이면 같은 값. C6은 아웃박스 행과 필드 대조 | 아웃박스 행을 읽어 돌려주기: E1 이전에 마감된 달은 행이 없다(조회 불가), 로그를 질의 저장소로 쓰게 된다. 두 출처가 같다는 것은 C6이 증명 |
| Q5 | ⑥ 스냅샷 부트스트랩 | **조건부로 넣는다 — 마지막 단계, 비용이 커지면 뺀다.** 범위: **아웃박스가 비어 있을 때만** 도는 잡 `outboxBootstrapJob`(비어 있지 않으면 거부): `AgentSnapshot`(기존 잡 재사용), 원장마다 `LimitLedgerOpened`(현재 한도·보험료) + 기존 전기 사슬 `LimitLedgerPosted`(`posting_seq`·`calc_id`·`accumAfter` 누적 — 포털 Phase 2 D-6이 라인 없이 받는다), `SCHEDULED`·`HELD` 분급마다 `DeferralScheduled`(+ HELD면 `DeferralStatusChanged(SCHEDULED→HELD, POLICY_NOT_INFORCE)`), 닫히지 않은 채권마다 `ClawbackReceivableUpdated`, **열린 달** 레코드마다 `CommissionCalculated`(마감 때 포털 합산이 엔진과 맞게). 전부 v1 계약 안 | 아웃박스가 이미 찬 테넌트(E1 이후 전기만 나간 원장 — `Posted`가 `Opened`보다 먼저 온다)·지난 달 명세 이력은 v1로 표현할 수 없다 → **계약 v2 `TenantSnapshot`** 항목(보고만). 아예 안 넣기: 엔진 운영 중 포털 도입 경로가 계속 없다 |
| Q6 | ⑤를 CI에서 | **된다** — 풀 티어가 이미 GitHub 러너에서 Oracle Free를 띄운다. 새 잡 `로컬 스택(A-1 피드)`(PR·main만): compose로 Oracle(healthcheck) → 마이그레이션 → 시드 → `commission-localstack` 실행 → 앱 기동 → `curl` 피드 → `a1_portal_150000_e2.jsonl`과 줄 단위 바이트 비교. 예상 6~9분 | 사용자 재현 기록만: C8을 손에 맡기게 된다 |
| Q7 | 포털 계약에서 엔진이 줄 수 없는 것 | §4 요청 R1~R9 — **②는 그 포털 PR 병합 뒤 착수**(E1 §0과 같은 방식). ①·③·④·⓪·⑤는 v1.2(`3f876b6`)로 진행 | 엔진이 계약에 맞춰 값을 지어내기(한도 미적용을 0으로 등): 포털이 "한도 0"으로 읽는다 |
| Q8 | (P6) 두 번째 판의 `StatementEstimated` 위치 | **첫 판 뒤에 추가** — 해약 뒤 202702 잠정 명세(음수 순액 사례). 첫 판 ⊂ 두 번째 판(바이트) 성립 | 202608 마감 앞(포털 A-1의 seq 7 자리): 포털 A-1 순서와 같아지지만 첫 판의 seq 6 이후 전부가 밀린다 — 포털은 Phase 2에서 들인 `a1/`을 통째로 다시 들여야 하고(골든 뷰의 `source_seq`·`last_seq`도 바뀜) C9 바이트 부분집합이 거짓이 된다. 포털의 ESTIMATED→CONFIRMED 전이 시험(포털 Phase 2 C6)은 엔진 C2 골든 사이클의 스트림(별도 파일)으로 줄 수 있다 |
| Q9 | (P8) A-1 시계 | **단계 시계**: 사실마다 업무 시각 — 계산 2026-08-01T09:00, 마감 2026-09-05T18:00, 지급 2026-09-10T10:00, 해약 2027-02-15T09:30(포털 샘플의 시각과 맞춘 것), 잠정 명세 2027-02-16T02:10(+09:00). 첫 판은 포털이 바이트로 들이므로 ⓪에서 정하면 바꿀 수 없다 | 고정 시계 하나(E1 골든처럼 전부 2026-09-05T18:00): 결정적이지만 계산·해약·지급이 같은 시각이라 포털 화면·대사(`recon_result.run_at` = `occurredAt`)의 뜻이 사라진다 |

## 2. 설계 (항목별 — "엔진 사실 → 설계")

### ⓪ A-1 정본 스트림

- **엔진 사실**: `PortalA1ComparisonTest:47-60`이 입력을 코드에 박고 `OutboxEngine`(인메모리 전 조립 + 데코레이터)으로 돈다. 골든 스냅샷은 `OutboxGoldenTest`가 `outbox-golden/*.jsonl`과 바이트 대조(E1).
- **설계**: 시나리오를 값으로 뽑는다 — `A1Scenario(record: monthlyPremium, contractDate, incentiveAmount, closeYm, payoutRunSeq, cancelDate, elapsedInstallments, steps(시각))`, 실행기 `A1ScenarioRunner.run(engine, scenario)` — `commission-outbox` **testFixtures**에 둔다. ⑤의 `commission-localstack`이 `implementation(testFixtures(project(":commission-outbox")))`로 같은 클래스를 쓴다(운영 jar에는 들어가지 않는다). 출력 `outbox-golden/a1_portal_150000.jsonl`(Envelope 한 줄씩, `EnvelopeJson` 직렬화 = 피드 바이트). 시험 `PortalA1StreamTest`: 다시 돌려 파일과 바이트 대조(갱신은 `-Pgolden.update` 명시 때만), 각 줄 포털 계약 검증, 위 값(한도·FY·삭감·지급·환수) 단언. 300,000 입력의 E1 차이 표는 그대로 둔다(같은 실행기, 다른 입력).
- **첫 커밋으로 먼저 push**(지시문). 그 시점 `UPSTREAM`은 아직 `f54d008` — A-1 스트림에는 v1.2가 좁힌 두 타입(`DeferralStatusChanged`·`ClawbackReceivableUpdated`)이 없어 v1.1.1·v1.2 판정이 같다(⓪b에서 다시 검증).
- **⓪b `UPSTREAM` = `3f876b6`**: 사본 재들이기(포털 `contracts/CHECKSUMS` 목록 그대로 — 샘플 17개가 세 디렉터리로), `CHECKSUMS`, `PortalContract.sampleNames()`가 디렉터리를 포함한 상대 경로를 돌려주게, `PortalA1ComparisonTest`·`PortalContractAgreementTest` 경로, 전역 검증기 재실행(위반 0), `PortalVocabularyTest`(엔진 열거 두 개 = 계약 열거, 글자·순서까지).
- **두 번째 판**(E2 끝): 같은 시나리오 + 해약 뒤 `statementEstimateJob`(Q8) → `a1_portal_150000_e2.jsonl`. `PortalA1StreamTest`가 "첫 판의 모든 줄이 두 번째 판의 같은 위치에 바이트로 있다"(앞부분 일치 = 추가만)를 단언(주입 S10).

### ① `StatementEstimated`

- **엔진 사실**: 지급은 CLOSED 월만(`PayoutService:165-169`), 마감은 도래 분급을 넣는다(P1), 상계는 쓰기(P2), 세율 상수(P9). 계약 payload: `agentId`·`closeYm`·`gross`·`clawbackOffset`·`adjustment`·`withholdingTax`·`net`·`basisSeq`·`asOf`(전부 필수).
- **설계**:
  - `commission-settlement`에 `StatementEstimator`(Spring 무의존). 입력은 **읽기 전용 포트 묶음** `EstimateReads`(레코드 조회 `findByCloseYm`, 도래 조회 `findDue`, 유지 판정, 채권 `findOffsettable`, 정정 `findByCloseYm`, 마감 상태) — 저장소 전체가 아니라 함수 참조라 쓰기 메서드가 손에 없다.
  - 산출(설계사마다): 레코드(AGENT) + 도래 미리보기(DEFERRED 가상 줄) → `gross` = Σ CLAWBACK 아님, `clawbackOffset` = Σ CLAWBACK, `grossNet = gross + clawbackOffset`(= `NetAmountCalculator.netOf`를 단언 — E1 빌더와 같은 단언), `adjustment` = Σ 그 달 귀속 정정. `PayoutMath.settle(grossNet, offsettable, taxPolicy)` → 음수면 지급 0·세액 0·`net 0`(채권화 예정), 양수면 미리보기 상계 → `payable` → 세액 → `net = payable − 세액`. `PayoutService.settleOne`도 같은 `PayoutMath`를 쓰게 고친다(동작 무변경 — 골든 24·지급 IT가 증명). 정정은 지급에 들어가지 않는다(엔진 E1-2)는 사실 그대로 `net`에도 없다.
  - 대상 설계사 = 그 달 레코드·정정·도래 미리보기 중 하나라도 있는 AGENT(E1 수용 심사 Q1과 같은 규칙).
  - 마감·마감 중 달은 거부(`CLOSED`·`CLOSING` → 잡은 건너뜀·로그, ③은 422 — 주입 S3).
  - 발행: `commission-outbox`의 `EstimatePublisher`(빌더 `PortalPayloads.statementEstimated`) — 잡 `statementEstimateJob`(`commission-batch`): ⓐ 부여(쓰기 tx) → ⓑ 산출(읽기 전용 tx) → ⓒ 설계사마다 적재(쓰기 tx, 아웃박스 행 하나씩). `asOf` = 주입 시계, `basisSeq` = Q3. 같은 (설계사, 월)에 다시 돌리면 다시 낸다(예상은 시점마다의 사실 — 포털은 최신을 쓴다).
- **잠금**: 읽는 조회 다섯에 `FOR UPDATE`가 없다(`grep` 결과: `FOR UPDATE`는 `OutboxMapper:35`·`CommCalcMapper:68`(`transition`)·`LimitLedgerMapper:47`·관리 화면 둘뿐). ①은 원장을 읽지 않는다.

### ② 시뮬레이션 2종

- **엔진 사실**: P3·P4·P14~P16. Step 조립은 `CalcPipelineConfig:83-94`에 한 번, 골든은 `GoldenEngine:144-165`에 따로.
- **설계**:
  - Step 목록을 함수 하나 `CalcSteps.standard(ledgerStore, calcReader, incentiveRepository)`로 뽑아 **운영 계산기와 dry-run 파이프라인이 같은 함수로** 만든다(조립 일치 시험에 dry-run 파이프라인의 Step 클래스·순서 = 운영 — 주입 S12).
  - 오버레이 저장소(`commission-settlement`의 `simulation` 패키지, 시뮬레이션 1회 수명): `OverlayLimitLedgerStore` — `find`·`getOrCreate`는 **잠금 없는 조회**(새 매퍼 `peekByKey`, `FOR UPDATE` 없음)로 읽어 메모리 사본, 없으면 메모리에서 개설, `save`는 사본만. `OverlayCommCalcReader` — 원 저장소의 조회만(삽입은 메모리 목록에). 운영 빈(`PublishingStores`)은 쓰지 않는다.
  - new-contract: `NEW`(회차 1) + 초년도 회차 `PAYMENT` 2..N(FY 요율이 있는 회차까지, `paymentTermMonths`로 상한) 가상 이벤트를 차례로 dry-run(같은 오버레이에서 누적) → `fyCommissionByInstallment`(FY_COMM 라인), `incentives`(INCENTIVE 라인 — `code` = 시책 코드(`IncentiveV2Step:127`의 `RuleVersionRef` key), `amount` = 삭감 뒤, `limitIncluded` = 유형 속성), `limitAmount`·`projectedAccum` = 오버레이 원장, `deferral` = `DeferralSplitStep` 결과, `ruleSnapshotId`(Q2), `generatedAt` = 시계. `incentiveCodes`가 오면 그 코드의 시책만 남긴다(R3 — 뜻 확정 요청).
  - policy-event: 그 계약의 저장된 이벤트(`POLICY_EVENT`)에서 보험사·상품·체결일·보험료를 읽어 가상 이벤트를 만들고 dry-run → `clawbackAmount`(CLAWBACK 라인), `offsetAgainstMonth`(귀속월 = 해석기 — 그 달 `grossNet` 전·후, ①의 산출기 재사용), `receivableRemaining`(열린 채권 잔액 + 음수면 새 채권 예정), `cancelledDeferrals`(그 계약·설계사의 `SCHEDULED` 스케줄 — `DeferralCancelListener`가 할 일), `ruleSnapshotId`. `REDUCE`는 R4의 `newMonthlyPremium`, `PAYMENT_OVERDUE`·모르는 계약은 R6 응답.
  - 컨트롤러 `SimulationInternalController`(`commission-api`, `/internal/v1/simulations/*`, 서비스 토큰 체인), 응답 바이트를 컨트롤러가 직접 직렬화(피드와 같은 방식). 저장 0, 레이트 리밋 0.

### ③ 명세 조회

- `GET /internal/v1/agents/{agentId}/statements/{ym}?mode=` (`StatementQueryController`). `ESTIMATE` = ①의 `StatementEstimator`를 지금(발행 없음, `basisSeq` = 읽기 `headSeq`, `asOf` = 시계) — 마감·마감 중 달이면 422 `MONTH_CLOSED`(R6). `CONFIRMED` = Q4, 마감 전이면 404 `STATEMENT_NOT_CONFIRMED`(R6 — 지금 계약에 404가 없다), 그 달 레코드·정정이 없는 설계사도 404. 형식 오류 400 `INVALID_REQUEST`(피드와 같은 Problem).

### ④ `ClosedMonthGuardedCalcStore` 조립

- `PersistenceConfig`의 `CommCalcStore` 빈 = `new ClosedMonthGuardedCalcStore(publishing.calcs(), settleCloseStore)` — **가드가 바깥**(거부된 insert는 발행 데코레이터에 닿지 않는다). 골든 엔진 전 조립·`OutboxEngine`에도 같은 순서로 넣어 골든 24·E1 골든 5가 가드를 지나며 동일함을 증명. `AssemblyParityTest`에 "운영 `CommCalcStore` 빈의 바깥 클래스 = 가드" + 골든 동형(주입 S5). P10을 설계서 §6.4에.
- ①은 insert가 없고, ②는 오버레이(메모리)라 가드에 닿을 일 자체가 없다 — "우회하지 않음"은 "insert 0"으로 증명(C1).

### ⑤ 로컬 스택 한 명령

- `localstack/compose.yaml`(Oracle Free — 카탈로그의 digest, healthcheck), `localstack/up.sh`: ① 무작위 서비스 토큰을 만들어 `localstack/.state/engine-token`(권한 600, `.gitignore`)에 쓰고 SHA-256만 `ENGINE_SERVICE_TOKEN_SHA256`으로 넘긴다(저장소·로그에 값 0 — `up.sh`는 토큰을 출력하지 않는다) ② Oracle 기동 대기 ③ `./gradlew :commission-localstack:run`(Flyway + 시드 SQL + A-1 시나리오, 단계 시계·순차 ID) ④ `bootRun`(테넌트 `GA01`, 기본 프로파일 — prod 아님이라 placeholder 가드 통과) ⑤ `up.sh verify` = 피드를 끝까지 받아 두 번째 판과 비교. README "로컬 실행"(명령·소요·포털 `e2e/engine-feed/`가 읽는 토큰 파일 경로).

### ⑥ 스냅샷 부트스트랩 (조건부 — Q5)

- 위 Q5 범위. 잡 `outboxBootstrapJob`(`commission-batch`), 빌더는 E1 것 재사용, 아웃박스 비어 있음 검사는 잡 첫 단계(같은 tx에서 `OUTBOX_SEQ` 잠금 → 행 0 확인). 시험: 부트스트랩 스트림 → 포털 계약 통과, 비어 있지 않으면 거부.

### 모듈

```
commission-settlement  + estimate/(StatementEstimator·EstimateReads·PayoutMath)  + simulation/(오버레이·DryRunSimulator)
commission-calc        + CalcSteps(Step 목록 함수 — 운영·dry-run 공용)
commission-clawback    + previewConsume(읽기 전용, consume과 같은 반복)
commission-outbox      + EstimatePublisher·빌더 statementEstimated  + testFixtures A1Scenario·A1ScenarioRunner(운영 jar 밖)
commission-infra       + LimitLedgerMapper.peekByKey(잠금 없음)
commission-api         + SimulationInternalController·StatementQueryController
commission-batch       + statementEstimateJob(·outboxBootstrapJob)
commission-app         조립: 가드 바깥, WithholdingTaxPolicy @Bean, 시뮬레이터·산출기 빈
commission-localstack  (신규) 시나리오 실행 main — 앱 설정 + 단계 시계·순차 ID. 운영 jar 밖
```

업무 모듈 쪽 변경: `PayoutMath` 추출(`settleOne` 동작 무변경), `previewConsume`, `CalcSteps` 추출, `peekByKey`. **Step 클래스 무변경**(B-12).

## 3. 완료 기준 → 시험·주입

| # | 시험(이름은 엔진 관례 — 한글 메서드) | 주입 |
|---|---|---|
| C1 | `DryRunNoWriteOracleIT#잠정명세_시뮬레이션_명세조회_전후_업무표가_같다` — 표마다(`COMM_CALC`·`LIMIT_LEDGER`·`LIMIT_LEDGER_DTL`·`DEFERRAL_SCHEDULE`·`CLAWBACK_RECEIVABLE`·`CLAWBACK_OFFSET_HIST`·`AGENT_SETTLEMENT`·`AGENT_SETTLEMENT_CALC`·`PAYOUT_STATEMENT`·`POLICY_EVENT`·`SETTLE_CLOSE`·`ADJUSTMENT`) PK 순 전 컬럼 해시 전후 같음, 아웃박스는 기존 행 바이트 동일 + 새 행 = `StatementEstimated`만(①) / 0(②③) | **S1** 산출기가 `consume`을 부름 → 채권 표 해시 달라짐. **S6** 오버레이 `getOrCreate`가 운영 저장소로 위임 → `LIMIT_LEDGER` 해시·아웃박스 `LimitLedgerOpened`. **S15** ③ `ESTIMATE`가 발행 → 아웃박스 +1 |
| C2 | `EstimateEqualsActualGoldenTest` — 케이스 ① 기본 ② 채권 상계(앞 달 음수 → 채권 → 이번 달 상계) ③ 정정 포함 ④ 음수 순액 채권화 ⑤ 분급 도래(P1). 각각 ① → 새 사실 없이 마감·지급 → 다섯 필드 대조 표(보고서) | **S8** 도래 미리보기 제거 → ⑤ 실패. **S9** 상계 미리보기 제거 → ② 실패. **S13** 산출기에 다른 세율 인스턴스 → 전 케이스 세액 실패 |
| C3 | `DryRunLockOracleIT#원장_잠금_중에도_잠정명세와_시뮬레이션은_기다리지_않는다` — 다른 커넥션이 원장 `FOR UPDATE`를 쥔 채로 ①②③을 JDBC 쿼리 타임아웃 2초로 실행 → 전부 완료 | **S7** 오버레이가 잠그는 `find` 사용 → 타임아웃 |
| C4 | `RuleSnapshotIdTest`(단위 — 같은 목록 같은 값, 버전 하나 바꾸면 다른 값, 순서 무관) + `SimulationDeterminismIT`(고정 시계로 2회 → 응답 바이트 같음) | **S2** `ruleSnapshotId`를 시계에서 → 실패 |
| C5 | `SimulationContractTest` — 응답이 포털 사본 스키마 통과: 한도 초과(150,000), 분급 커브 있음/없음, 2026-06-30 이전 체결(R2 병합 뒤), 해약 6/7회차 경계(R4 `elapsedInstallments`) | — |
| C6 | `StatementQueryOracleIT#CONFIRMED는_발행된_이벤트와_같다`(공통 필드 단위, 지급 전·후), `#마감_전_CONFIRMED는_404`, `#마감월_ESTIMATE는_422` | **S4** `CONFIRMED`가 REVERSED를 거름(상태 필터) → 실패 |
| C7 | `AssemblyParityTest`(가드 바깥·골든 동형·dry-run Step 목록 = 운영), `ClosedMonthGuardAssemblyIT#해석기를_우회한_마감월_insert는_거부되고_발행되지_않는다`, 골든 24 + E1 골든 5(가드 포함) | **S5** 운영 조립에서 가드 제거 → 실패. **S12** dry-run 파이프라인에서 Step 하나 제거 → 실패 |
| C8 | CI 잡 `로컬 스택(A-1 피드)` + `localstack/up.sh verify` | **S11** 시드 SQL의 FY 요율 하나 변경 → 바이트 비교 실패 |
| C9 | `PortalA1StreamTest` — 두 판 포털 v1.2 사본 검증 통과, 첫 판 = 두 번째 판의 앞부분(바이트) | **S10** 두 번째 판에서 잠정 명세를 202608 마감 앞에 → 실패. **S3** 마감월 잠정 명세 허용 → `StatementEstimatorTest#마감월은_내지_않는다` 실패 |
| C10 | 전역 검증기(타입 13종 — `StatementEstimated` 추가, 위반 0), 골든 24 + 5 동일, jqwik 0, CI 3단(빠른·풀·no-docker) + 로컬 스택 잡, `PortalVocabularyTest` | **S14** 빌더에서 `basisSeq` 제거 → 전역 검증기 실패. **S16** 엔진 열거에 값 하나 추가 → `PortalVocabularyTest` 실패 |

주입 **ID 단위 16건**(S1~S16) — 지시문 최소 다섯(S1·S2·S3·S4·S5)을 포함.

## 4. 포털 계약 요청 목록 (Q7 — 포털 PR이 먼저, 엔진은 그 병합 커밋으로 `UPSTREAM`을 다시 올린 뒤 ②)

| # | 요청 | 종류 | 근거 |
|---|---|---|---|
| R1 | `NewContractRequest.insurerCd` 선택 추가 | 추가 | P15 — 요율 키가 (보험사, 상품), 상품 마스터 없음 |
| R2 | `NewContractResult.limitAmount`·`projectedAccum` → 선택(부재 = 한도 미적용) | 완화 | P14 — 2026-06-30 이전 체결 |
| R3 | `incentives[].amount` 설명 "한도 삭감 뒤 지급 예정액" + 선택 `limitCutAmt`, `incentiveCodes` 설명 "엔진 시책 마스터 코드 — 주면 그 시책만 계산" | 설명·추가 | `CommissionCalculated.amount`와 같은 정의(I1) |
| R4 | `PolicyEventRequest`에 선택 `elapsedInstallments`(≥ 1)·`newMonthlyPremium`(REDUCE) | 추가 | P16 — 회차 근사, 재산정 입력 |
| R5 | `PolicyEventResult` 설명: `clawbackAmount` ≤ 0(`CommissionCalculated` CLAWBACK과 같은 부호), `offsetAgainstMonth.before/after` = 그 달 세전·상계 전 순액(`gross + clawbackOffset`), `receivableRemaining` = 이 이벤트 뒤 열린 채권 잔액 합 | 설명 | 지금 뜻이 없다 |
| R6 | 시뮬레이션·명세 조회에 `400 INVALID_REQUEST`·`404`(`AGENT_NOT_FOUND`·`POLICY_NOT_FOUND`·`STATEMENT_NOT_CONFIRMED`)·`422`(`UNSUPPORTED_EVENT_TYPE` — `PAYMENT_OVERDUE`, `MONTH_CLOSED` — 마감월 `ESTIMATE`) Problem 응답 | 추가 | 지금 200만 있다 |
| R7 | `StatementEstimated.basisSeq` 설명(하한 — Q3), 선택 `receivableOffset`·`carriedReceivable`·`payable`(지급 런과 같은 정의 — `PayoutCompleted`와 짝) | 설명·추가 | 예상의 상계가 지금 보이지 않는다 |
| R8 | `getStatement` `CONFIRMED` 설명: `StatementConfirmed` 모양 + 지급 뒤 `withholdingTax`·`net`(그 달 지급 런 합) | 설명 | P11 |
| R9 | (요청 아님 — 기록) `generatedAt` 때문에 C4 바이트 동일은 고정 시계 조건 | — | P12 |

전부 추가·완화·설명이라 이벤트 `version` 1 그대로(포털 §4.5). R2만 응답 필수의 완화다.

## 5. 단계·커밋

1. 이 계획 → 승인 보관 + 계획 갱신 1커밋.
2. **⓪a** A-1 첫 판(`A1Scenario`·실행기·`a1_portal_150000.jsonl`·`PortalA1StreamTest`) — **먼저 push**(포털에 커밋 해시 전달).
3. **⓪b** `UPSTREAM` `3f876b6`(사본·CHECKSUMS·샘플 경로·`PortalVocabularyTest`·전역 검증기).
4. **④** 가드 조립(골든 조립 포함, 조립 시험).
5. **①** `PayoutMath`·`previewConsume` 추출(동작 무변경 커밋 따로) → `StatementEstimator`·발행·잡 → C1(①)·C2·C3(①).
6. **③** 명세 조회(C6). R6 병합 전에는 404·422를 우리 Problem 형식으로 내되 계약 사본 검증은 200 응답만(병합 뒤 추가).
7. **②** 시뮬레이션 — **포털 R1~R8 PR 병합 + `UPSTREAM` 재상향 뒤.** `CalcSteps` 추출 → 오버레이 → 두 엔드포인트 → C1(②)·C3(②)·C4·C5.
8. **⓪** 두 번째 판(`a1_portal_150000_e2.jsonl`).
9. **⑤** 로컬 스택 + CI 잡 + digest 고정(P7).
10. **⑥** 조건부 부트스트랩(Q5 — 비용이 크면 빼고 보고).
11. 문서(설계서 §6.4·§6.8·§7·§10·§11, `db-migrations.md`(새 V 없음 예상 — `peekByKey`는 SELECT뿐), README, `설계고찰.md`), 보고서, 태그 `e2`, PR(병합은 수용 심사 뒤).

## 6. 위험·열린 것

- **②가 포털 PR에 묶인다.** 포털 쪽 R1~R8 병합이 늦으면 ②만 남기고 나머지를 먼저 끝낸다(보고서에 대기 표시) — 범위를 줄이지 않고 순서만.
- **C2의 "새 사실 없이"**: 마감 자체가 도래 분급(P1)과 `StatementConfirmed`를 낸다 — "새 업무 입력(정책 이벤트·정정) 없이"로 읽는다.
- **`PolicyStatusProvider`가 운영 placeholder(항상 유지)**다(`PlaceholderPolicyStatusProvider`) — 예상과 실제가 같은 판정기를 쓰므로 C2에는 영향이 없지만, 실 판정기가 생기면 "산출 시점의 유지 판정 ≠ 마감 시점"이 예상 오차의 출처가 된다(설계서에 한 줄).
- **기존 `/api/commissions/simulate`(P4)**는 파이프라인과 다른 숫자를 낼 수 있다 — E2는 건드리지 않고 기록만.
- **A-1 첫 판은 되돌릴 수 없다**(포털이 바이트로 들인다) — Q9(시계)·Q8(위치)을 ⓪ 전에 승인받는다.
- **⑤ Oracle Free 기동 시간**(CI 2~4분) — 풀 티어와 별도 잡이라 병렬.
- 이 계획은 `commission-localstack` 모듈 하나를 새로 만든다(운영 jar 밖 — `bootJar`가 없는 `application` 모듈). 새 외부 의존성은 없다(Testcontainers·compose는 이미 쓰는 이미지, `curl`·`jq`는 러너 기본).
