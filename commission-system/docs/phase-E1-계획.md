# E1 계획 — 엔진 아웃박스·이벤트 피드 (지시문 `docs/phase-E1-지시문.md` v1.0)

> 브랜치 `work/e1`(`85b4c71` + 지시문 보관 `3e369e7`). **승인 뒤 2단계.** 포털 계약 v1.1은 병합됐다 — 포털 PR #2 병합 커밋 **`f30d026ae51cb7885e2536b3afc1b5a038c3a140`**(포털 `main` CI run 38954273360 success). 이것이 `contracts/shared/ga-agent-portal/UPSTREAM`이다.

## 승인 반영 (2026-10-17, `docs/phase-E1-계획승인.md`) — 아래 본문보다 우선한다

Q1~Q5·P4~P12 전부 권장안으로 승인. 본문과 달라지거나 더해진 것:

| # | 반영 |
|---|---|
| `UPSTREAM` | 포털 계약 **v1.1.1** 병합 커밋(포털 PR — L1 `LimitLedgerRebased.postingSeq` 선택, L2 `paidAt` 뜻, L3 `limitCutPolicy` 뜻). `f30d026`이 아니다. 해시를 받기 전에는 2단계로 가지 않는다 |
| Q1 / P1 | `LIMIT_DEFER` 발행 없음. 설계서 §14에 "DEFER_AFTER_FY 실제 이연(초년도 종료 후 지급) 미구현 — 새 Step", §6.1-4 "② 초년도 종료 후 이연"에 "(현재 삭감과 동일 동작, §14)" |
| Q2 / P2 | `LimitLedgerRebased`를 **E1에서 발행**. `postingSeq` = 마지막 `posting_seq`, 전기가 없으면 생략. `reason` 닫힌 열거 **`PREMIUM_REDUCED`·`PREMIUM_INCREASED`**(한도가 줄면·늘면). `LIMIT_LEDGER_HIST`를 아무도 쓰지 않는 사실은 설계서 주석(표는 지우지 않는다) |
| Q3 / P3 | 분급 취소 리스너·reversal 훅을 **별도 커밋**으로 앱 조립에. 같은 커밋에 **조립 시험**: 운영 조립의 `CalcResultListener`·`ReversalHook` 클래스 집합 = 골든 엔진 조립의 집합. 주입 **I13**(조립에서 하나 제거 → 실패). DECISIONS·`설계고찰.md`에 "E1 이전부터의 결함, E1에서 수정" |
| Q4 / P9 | ArchUnit 없음. 원문 스캔: 업무 모듈 `main`의 `import ga.comm.outbox` 0 + `project(":commission-outbox")`를 참조하는 `build.gradle.kts`는 `infra`·`api`·`batch`·`app`뿐. I12는 Gradle 순환 |
| Q5 / P7 | 앱 전역 `Clock` 빈 하나(공시 등급의 Asia/Seoul 시계와 통합 — 시간대 그대로). `SettleCloseStore.transition`은 **`CLOSED`일 때만** `closed_at`을 쓴다(CLOSING·OPEN 전이는 건드리지 않는다 — 지금의 덮어쓰기에서 바뀌는 동작: 설계서 §7·§5.9 주석, 보고서) |
| P4 | `PayoutCompleted.gross` ≠ `gross_net` — 지시문 D-6 오류 |
| P5 | `AdjustmentPosted`만. `PayoutCompleted.adjustment` 없음, `StatementConfirmed.adjustment`는 넣는다. 설계서 §14 "정정이 마감·지급에 읽히지 않는다 — 호출자도 없다" |
| P6 | `PAYOUT_STATEMENT`(V107): `run_seq`·수급자·`payable`·`income_tax`·`local_tax`·`net_pay`·`paid_at`·`settlement_id` FK + `UNIQUE (settlement_id)`. `paid_at` = 정산행 기록·PAID 전이 시각. 설계서 §5.9, §7 ③ "지급명세 영속" |
| P8 | 피드 핸들러: **부여는 쓰기 트랜잭션 하나로 먼저 끝내고**(한 번에 최대 N건 — 파라미터), 읽기는 그 뒤 **별도 읽기 전용 트랜잭션** |
| P11 | `ClosedMonthGuardedCalcStore`가 운영 조립에 없다는 사실을 설계서 §6.4 문구와 대조해 결함/의도 판단을 보고서에 |
| §3 | 증명 ③에 **단일 인스턴스 가정**(IDENTITY 단조성은 단일 인스턴스에서 — RAC 범위 밖). H2 티어는 `synchronized` 인메모리와 공통 V105 둘 다 |
| §5 | 검증기 1.5.9 ↔ 3.0.6 판정이 다르면 **엔진 쪽을 올리지 않고** 어느 쪽이 2020-12에 맞는지 적어 보고 — 그 자리에서 결정 |
| C10 | 포털 A-1 대조 = 차이 표 + **타입 순서 일치 여부**를 따로 |
| 주입 | **13건**(I1~I13), ID 단위 |
| 지시문 오류 | 보고서 "지시문 오류"와 `설계고찰.md`에 여덟 건: D-4 `LIMIT_DEFER`, D-6 `gross`(= `gross_net`), D-9 `LIMIT_LEDGER_HIST`·`postingSeq ≥ 1`, D-10 취소 리스너, D-14 시계, D-15 ArchUnit, D-1·D-8 스케줄러·명령, §2-4 리스너 구현 |

이번 계획을 위해 엔진을 다시 읽었다(2026-10-17, `85b4c71`). **지시문의 전제 가운데 엔진 코드와 다른 것이 열두 개 있다(§0).** 그중 셋(P1·P2·P3)은 계약이나 업무 동작을 바꿔야 풀리므로 지시문 §0 "바꿀 일이 있으면 멈추고 보고"에 따라 결정을 묻는다. 나머지는 권장안으로 진행하고 보고서에 적는다.

## 0. 지시문 전제 ↔ 엔진 사실

| # | 지시문 | 엔진 사실(파일:행) | 권장 |
|---|---|---|---|
| **P1** | D-4 "한도 이연분(DEFER_AFTER_FY)도 `DeferralScheduled`(reason `LIMIT_DEFER`) — `DeferralSchedulePoster`가 만드는 스케줄에서" | **한도 이연은 아무것도 만들지 않는다.** `LimitGateStep:98-109`은 `over_limit_action`으로 메모·트레이스 `"action"`만 바꾸고 두 값 모두 `limit_cut_amt`에 삭감액을 둔다. `LIMIT_DEFER`는 저장소 어디에도 없다. 스케줄은 FY_COMM 곡선 분할(`DeferralSplitStep`)만 만든다 | E1은 `LIMIT_DEFER` 스케줄을 **발행하지 않는다**(지어낼 수 없다). `CommissionCalculated.limitCutPolicy` = 룰의 `over_limit_action`(`DEFER_AFTER_FY` 그대로)만. 한도 이연을 실제로 만드는 것은 새 Step(B-12) — 엔진 설계서 §14 미결정으로 올리고 E1 범위 밖. **포털 A-1 샘플 06(`LIMIT_DEFER` 200,000)은 엔진이 재현할 수 없다** → 차이 표 + 포털 샘플 수정 후보 |
| **P2** | D-9 "`LimitLedgerRebased`는 `PremiumRepriceStep`의 `LIMIT_LEDGER_HIST` 기록마다" | **`LIMIT_LEDGER_HIST`는 어떤 코드도 쓰지 않는다**(DDL `V3`와 시험의 표 목록에만). `PremiumRepriceStep:47-77`은 `reprice` 뒤 `save`로 `monthly_premium`·`limit_amount`만 바꾼다. 계약 `LimitLedgerRebased.postingSeq`는 필수·≥ 1인데 재산정은 전기(DTL)가 아니어서 순번이 없다 — 전기가 하나도 없는 원장의 재산정이면 줄 값이 없다 | 원장 저장소 데코레이터가 `save`에서 `limit_amount` 변화를 보고 발행(Step 무변경). `postingSeq` = 그 시점 마지막 `posting_seq` — **0일 수 있으므로 포털 계약 `minimum: 1 → 0` 완화가 필요하다(포털 PR 먼저)**. 대안: E1에서 `LimitLedgerRebased`를 내지 않고 E2로 미룬다 |
| **P3** | D-10 "`DeferralCancelListener`/`DeferralReversalHook`(→ `CANCELLED`)" | **둘 다 앱 조립에 없다.** `CalcPipelineConfig:93-96` 리스너는 `LimitLedgerPoster`·`ClawbackPostProcessor`·`DeferralSchedulePoster`뿐, `ServiceConfig:120` `RevisionService` 훅은 `LimitReversalHook`뿐. 시험 조립(`GoldenEngine:115-122`)에만 있다 — **운영에서 스케줄은 `CANCELLED`가 되지 않는다** | 이건 E1 이전부터 있던 업무 결함이다. 권장: E1의 **별도 커밋**에서 두 개를 앱 조립에 더한다(Step 무변경, 골든 엔진과 같은 조립). 대안: 조립은 그대로 두고 결함으로 보고만(그러면 운영에서 `DeferralStatusChanged(CANCELLED)`는 나오지 않는다) |
| P4 | D-5·D-6 정의 | `gross_net` = `NetAmountCalculator.netOf(records)` — 그 지급분 레코드 **전부**(CLAWBACK·REVERSED·reversal 포함)의 합(`NetAmountCalculator:30-32`). 계약 v1.1 `gross` = Σ commType ∉ {CLAWBACK, ADJUSTMENT}(포털 DECISIONS CI-1) | 이벤트 빌더가 같은 레코드 집합을 유형으로 나눈다: `gross` = Σ CLAWBACK 아닌 것, `clawbackOffset` = Σ CLAWBACK, 그리고 **`gross + clawbackOffset = gross_net`을 빌더가 단언**한다. 상태 필터 없음. `PayoutCompleted.gross` ≠ `gross_net` — 지시문 D-6 "`gross`(= `gross_net`)"는 계약 정의와 맞지 않아 계약을 따른다 |
| P5 | D-12 "정정에 `COMM_CALC`도 만든다면 하나로" | `AdjustmentService.adjust`(`:37`)는 `ADJUSTMENT` 행만 만든다. `COMM_CALC`에 ADJUSTMENT 유형이 없다(`CommTypeCode:17-24`). **호출자가 없고**(시험만) **마감·지급 런이 ADJUSTMENT를 읽지 않는다** — 정정은 지금 지급되지 않는다 | `AdjustmentPosted`만 발행(이중 계상 없음 — 확정). `StatementConfirmed.adjustment` = Σ 그 `close_ym` 귀속 `ADJUSTMENT`. `PayoutCompleted.adjustment`는 **넣지 않는다**(선택) — 지급 순액에 들어 있지 않은 값을 지급 이벤트에 적으면 포털이 지급된 것으로 읽는다. "정정이 지급되지 않는다"는 엔진 결함으로 보고(엔진 §14) |
| P6 | D-6 영속 판단 | 지급명세(세액·순액)는 반환값과 Spring Batch 실행 컨텍스트에만 있다(`PayoutRunJobFactory:99-109`). `paid_at`은 어디에도 없다 | **B-15에 맞지 않으므로 `PAYOUT_STATEMENT` 표를 만든다**(`run_seq`·수급자·`payable`·`income_tax`·`local_tax`·`net_pay`·`paid_at`·`settlement_id`). `settleOne`이 같은 트랜잭션에서 저장하고, 발행은 그 저장 뒤 |
| P7 | D-14 `occurredAt` = `closed_at`·`paid_at`(주입된 시계) | `closed_at`은 DB `CURRENT_TIMESTAMP`(`SettleCloseMapper:22-26`)이고 CLOSING·OPEN 전이에서도 덮어쓴다. 마감·지급에 `Clock`이 없다(유일한 `Clock` 빈은 공시 등급용 `DisclosureGradeConfig:23-26`) | 앱 전역 `Clock` 빈을 쓰고 `MonthCloseService`·`PayoutService`가 생성자로 받는다. ④에서 `closedAt = clock.instant()`를 `SettleCloseStore.transition(…, closedAt)`으로 넘겨 DB와 이벤트가 같은 값이 되게 한다(매퍼가 `CURRENT_TIMESTAMP` 대신 인자 사용). `paid_at` = `clock.instant()` → `PAYOUT_STATEMENT.paid_at` |
| P8 | D-1 "부여 주기 기본 1초(배치 잡 또는 스케줄러)", D-8 "`outbox snapshot-agents` 명령" | 엔진에는 `@Scheduled`·CLI가 없다. 잡은 `POST /api/batch/{job}/run`(`BatchLaunchController:25-53`, OPERATOR/ADMIN)으로만 시작한다 | 부여는 **피드 호출 때 lazy**(같은 `OUTBOX_SEQ` 잠금) + 같은 일을 하는 배치 잡 `outboxSequenceJob`. 부트스트랩도 잡 `outboxAgentSnapshotJob`. 스케줄러를 새로 들이지 않는다(포털 폴링 주기 기본 10초가 lazy 부여를 끌어간다) |
| P9 | D-15 "ArchUnit에 추가" | 엔진에 ArchUnit이 없다(원문 스캔 시험 `NetAmountArchitectureTest`뿐). 모듈 경계는 Gradle 의존 그래프 | 역방향은 **Gradle이 이미 막는다**: `outbox`가 업무 모듈에 의존하므로 업무 모듈이 `outbox`를 의존하면 순환 → 빌드 실패. ArchUnit을 새로 들이지 않고 ① 그 순환(I12) ② 원문 스캔 시험(업무 모듈 main에 `ga.comm.outbox` 0, 기존 `NetAmountArchitectureTest` 방식)으로 증명. 대안: ArchUnit 추가(새 의존성 + 검증 메타데이터) |
| P10 | D-8 `orgPath` = `ORG_MST` 부모 체인 | 엔진 자신의 조직 해석(`AgentDirectoryMapper:12-35`)은 `AGENT_ORG_HIST`의 수준별 세 행(TEAM·BRANCH·HQ, `V8:30-32` PK 변경)을 `org_level` 순으로 쓴다 — 부모 체인을 걷지 않는다. 마스터 쓰기 경로도 없다(시드는 시험 SQL·CSV) | `orgPath`는 엔진의 해석과 같은 `AgentDirectory.orgChainOf`로(두 해석이 어긋나면 수수료 귀속과 화면이 다르다). `AgentSnapshot`은 부트스트랩 잡만 |
| P11 | C8 "`ClosedMonthGuardedCalcStore`가 막는 것을 이벤트에서 다시 단언" | 그 데코레이터는 운영 조립에 없다(`PersistenceConfig:58-61`은 Oracle 저장소 그대로) — 운영은 `CloseYmResolver`가 새 레코드를 다음 열린 달로 보낸다(`CalcPipelineConfig:54-62`) | 이벤트 쪽 불변식은 운영 경로(`CloseYmResolver`)로 시험한다: 마감 뒤 들어온 이벤트의 `CommissionCalculated.closeYm`은 다음 달. I9는 "리졸버를 우회해 마감월로 기록" |
| P12 | §2-4 "`CalcResultListener` 구현(`CommissionCalculated` 라인마다)" | `COMM_CALC`를 넣는 경로는 셋이다 — 계산(리스너 경유), **정정 reversal**(`RevisionService:63-71`, 리스너 아님), **분급 도래 DEFERRED**(`DeferralReleaseService:48-63`, 리스너 아님). 운영에서 `process()`를 부르는 곳은 없고(`recalcJob`의 `rebook`만) 정책 이벤트 수신 경로도 없다 | 리스너가 아니라 **`CommCalcStore.insert` 데코레이터**에서 발행 → 세 경로가 빠짐없이 나온다(§2). `PolicyEventObserved`는 `PolicyEventStore.upsertByKey` 데코레이터(새로 넣은 때만) — 운영 수신 경로가 생기면 그대로 나온다 |

그 밖에 확인한 것: `posting_seq`는 Oracle `save`가 `MAX+1`부터 꼬리 전기에 붙인다(`OracleLimitLedgerStore:80-100`), `getOrCreate`는 생성 여부를 돌려주지 않는다(`:45-77`), 분급 `replace`는 잠금·이전 상태 대조 없이 UPDATE(`DeferralScheduleMapper:53-56`), 채권 `findOffsettable`에 `FOR UPDATE`가 없다(`ClawbackReceivableMapper:31-37` — 지급은 수급자 단위 트랜잭션이라 같은 설계사 경합은 없다. 보고만).

## 1. 결정이 필요한 것 (요약)

| # | 질문 | 권장 |
|---|---|---|
| Q1 | P1 한도 이연 | E1은 `LIMIT_DEFER` 발행 없음 + 엔진 §14 + 포털 샘플 06 수정 후보 |
| Q2 | P2 `LimitLedgerRebased.postingSeq` | 포털 계약 `minimum 0` 완화 PR(포털 쪽 — 지시문상 그쪽이 먼저) 뒤 데코레이터 발행. 안 되면 E1에서 `Rebased` 보류 |
| Q3 | P3 분급 취소 조립 | E1 별도 커밋으로 앱 조립에 추가 |
| Q4 | P9 ArchUnit | 들이지 않는다 — Gradle 순환 + 원문 스캔 |
| Q5 | P7 마감 `closed_at` | 시계 값을 DB에도 쓴다(매퍼 인자) |

## 2. 발행 지점 표

원칙: **업무 모듈 코드는 고치지 않는다.** 발행은 `commission-outbox`의 **저장소 데코레이터**(포트를 감싸 같은 호출 = 같은 트랜잭션)와, 저장소로 잡히지 않는 두 사실(마감 확정·지급명세)만 업무 서비스의 **최소 리스너 포트**로. 데코레이터와 리스너는 앱 조립(`commission-app` 설정)·골든 엔진·Oracle IT 조립에 끼운다.

| 이벤트 | 발행 지점 | 트랜잭션 | payload ↔ 엔진 |
|---|---|---|---|
| `CommissionCalculated` | `OutboxCommCalcStore.insert`(데코레이터) — `recipient_type = AGENT`만 | 호출자: 계산 `inTx`/`recalcJob` 항목 tx, 정정 tx, 분급 도래(마감 closeStep tx·`deferralReleaseJob` tx) | `calcId`=`calc_id`, `agentId`=`recipient_id`, `policyNo`, `productKey`·`policySummary{contractDate, monthlyPremium}`=`POLICY_EVENT`(`event_id`로 같은 tx에서 읽음), `commType`, `amount`=`calc_amount`, `limitCutAmt`=`limit_cut_amt`, `limitCutPolicy`=트레이스 `LIMIT_GATE_V1`의 `action`(= 룰 `over_limit_action().name()`, `LimitGateStep:109` — 삭감 0이면 생략), `closeYm`, `reversalOfCalcId`=`reversal_of`, `basis.rate`=`applied_rate`(있을 때, 소수 문자열), `basis.payoutRate`=트레이스 `PAYOUT_RATE_V1.payoutRate`(있을 때), `basis.installmentNo`=`POLICY_EVENT.installment_no`(있을 때), `basis.ruleVersionIds`=`rule_versions`의 `type:key:ref` |
| `PolicyEventObserved` | `OutboxPolicyEventStore.upsertByKey` — 새로 넣은 행이고 유형이 CANCEL·LAPSE·WITHDRAW·REDUCE·REVIVE일 때 | `process()`의 tx | `eventType` 매핑(REVIVE→REINSTATE)은 빌더 한 곳 + 표 시험(D-7). `occurredOn`=`event_date` |
| `LimitLedgerOpened` | `OutboxLimitLedgerStore.getOrCreate` — 직전 `find`가 비었을 때(같은 tx, `FOR UPDATE`) | 계산 tx(게이트 단계) | `ledgerId`·`policyNo`·`agentId`·`contractDate`·`fyStart`·`fyEnd`·`monthlyPremium`·`limitMultiple`(소수 문자열)·`limitAmount`·`ruleVersionId` |
| `LimitLedgerPosted` | `OutboxLimitLedgerStore.save` — 이번 `save`로 새로 생긴 꼬리 전기마다 | 그 `save`의 tx(게이트·포스터·환수 차감·부활 재전기·reversal 훅 — 다섯 경로가 전부 `save`를 거친다) | `postingSeq`=꼬리 전기의 `posting_seq`, `calcId`, `amount`, `accumAfter`=그 전기 직후 누적(원장 잠금 안에서 앞 전기부터 차례로) |
| `LimitLedgerRebased` | 같은 `save` — `limit_amount`가 바뀌었을 때(Q2) | 계산 tx(재산정 단계) | `oldLimit`·`newLimit`·`monthlyPremium`·`reason`=`PREMIUM_REDUCED`(닫힌 어휘) |
| `DeferralScheduled` | `OutboxDeferralScheduleStore.createAll` | 계산 tx | `reason`=`CURVE`(엔진이 만드는 유일한 스케줄 — P1), `payCondition`, `curveVersionId`, `sourceCalcId` |
| `DeferralStatusChanged` | `OutboxDeferralScheduleStore.replace` — 이전 상태는 같은 tx에서 읽는다 | 도래(마감 closeStep·`deferralReleaseJob`), 해약(계산 tx), 정정(정정 tx) | `from`·`to`·`reason`(아래 어휘) |
| `ClawbackReceivableUpdated` | `OutboxClawbackReceivableStore.create`·`save` | 지급 `settleOne` 항목 tx | `receivableId`·`agentId`·`originCalcId`·`amount`·`remaining`·`status`(OPEN/OFFSET/CLOSED/WRITTEN_OFF) |
| `AdjustmentPosted` | `OutboxAdjustmentStore.create` | 호출자 tx(지금은 시험뿐 — P5) | `adjId`·`targetCalcId`·`agentId`(대상 레코드의 수급자 — ORG면 발행 안 함)·`amount`·`closeYm`(귀속월)·`reason` |
| `StatementConfirmed` | 새 리스너 포트 `MonthCloseListener.onConfirmed(ym, closedAt, records)` — `MonthCloseService.close` ④ 뒤 | 마감 closeStep tx | 설계사마다 1건: `gross`·`adjustment`·`closedAt`(P4·P5). `clawbackOffset`도 넣는다(마감 시점에 참 — Σ CLAWBACK 라인). 세액·순액 없음 |
| `PayoutCompleted` | 새 리스너 포트 `PayoutListener.onSettled(statement, row, records)` — `settleOne`이 `PAYOUT_STATEMENT` 저장 뒤 | 지급 항목 tx | `runSeq`, `gross`·`clawbackOffset`(P4), `receivableOffset`, `carriedReceivable`, `payable`, `withholdingTax`=소득세+지방세, `net`=`netPay`=`paidAmount`, `paidAt`. `adjustment` 없음(P5) |
| `AgentSnapshot` | 잡 `outboxAgentSnapshotJob`(P8) | 설계사마다 항목 tx | `name`=`agent_name`, `grade`(이력 있을 때), `orgPath`=`AgentDirectory.orgChainOf`(P10), `status`=`dismissed_on`, `validFrom`=관련 이력의 가장 늦은 `apply_from`. `role` 없음(엔진에 없다) |

`DeferralStatusChanged.reason`(닫힌 어휘, 빌더 열거): `DUE_RELEASED`(→RELEASED), `POLICY_NOT_INFORCE`(→HELD), `POLICY_TERMINATED`(해약·실효·철회 → CANCELLED), `SOURCE_REVERSED`(원본 정정 → CANCELLED). 데코레이터는 호출자를 모르므로 취소 둘은 **리스너 포트 대신 `replace` 호출 직전에 이유를 넘기는 얇은 오버로드**가 필요하다 — 이것만 분급 모듈의 포트 변경(`replace(entry, reason)` 기본 메서드)이다.

**ORG 제외 범위**(D-3): `COMM_CALC`·정산·조정 대상이 ORG인 행은 어떤 이벤트에도 없다 — `CommissionCalculated`(수급자), `StatementConfirmed`(AGENT 레코드만 합산), `PayoutCompleted`(AGENT 수급자만), `AdjustmentPosted`(대상 레코드가 ORG면 생략). 한도·분급·채권은 엔진에서 원래 AGENT 전용이다.

## 3. 순번 부여기 (D-1)

- `OUTBOX_EVENT(event_no IDENTITY PK, event_id CHAR(36) UNIQUE, seq NUMBER NULL UNIQUE, event_type, schema_ver, aggregate_kind, aggregate_id, occurred_at TIMESTAMP WITH TIME ZONE, payload CLOB, created_at)`, `OUTBOX_SEQ(id=1, last_seq)` 한 행.
- 부여: `SELECT last_seq FROM OUTBOX_SEQ WHERE id=1 FOR UPDATE` → `seq IS NULL`인 보이는 행을 `event_no` 순으로 `last_seq+1…` → `OUTBOX_SEQ` 갱신 → 커밋. 피드 호출 때(lazy)와 `outboxSequenceJob` 모두 같은 메서드.
- **설계서 §6.8 증명 문장**: ① 업무 트랜잭션은 `seq` 없이 넣으므로 롤백된 행은 번호를 소비하지 않는다(갭 0). ② 부여기는 커밋된 행만 본다(읽기 일관성) — 늦게 커밋된 행은 다음 부여에서 더 큰 번호를 받으므로 이미 나간 번호 사이에 끼지 않는다(포털이 건너뛸 일 0). ③ 같은 원장·같은 설계사의 두 사실은 엔진 잠금(원장 `FOR UPDATE`, 수급자 단위 지급 tx)이 커밋 순서를 정하고, 앞 트랜잭션의 행은 그 커밋 전에 `event_no`를 받았으며 뒤 트랜잭션은 그 커밋 뒤에야 진행하므로 `event_no` 순 = 커밋 순 = 인과 순이다. (같은 잠금을 공유하지 않는 두 사실 사이의 순서는 보장하지 않는다 — 포털은 그것에 기대지 않는다.)
- H2 티어: 같은 SQL(공통 `V105`). 인메모리 레퍼런스는 `synchronized`.

## 4. 모듈 의존

```
commission-outbox (Spring 무의존) ──► calc, limit, deferral, clawback, settlement ──► rule ──► domain
commission-infra  ──► outbox (Oracle OutboxStore·MyBatis)
commission-api    ──► outbox (EventFeedController)
commission-batch  ──► outbox (outboxSequenceJob·outboxAgentSnapshotJob)
commission-app    ──► 데코레이터·리스너 조립
업무 모듈 ──► outbox : 없음 (순환이라 Gradle이 거부 — I12)
```

업무 모듈 쪽 변경은 셋뿐: `MonthCloseListener`·`PayoutListener` 포트(settlement), `replace(entry, reason)` 오버로드(deferral), `Clock`·`closedAt` 인자(settlement — P7). Step 클래스는 하나도 고치지 않는다.

## 5. 계약 사본 (D-16)

- 기존 `contracts/api/v1/engine-disclosure.openapi.yaml` → `contracts/shared/ga-disclosure/api/v1/`(바이트 동일). 같은 디렉터리에 `events/v1/envelope-core.schema.json`. ga-disclosure `UPSTREAM`은 `688cc46…` → **`db82d5d4ca35de2e16ac1d6238fef5d39ae304a2`**(두 파일 모두 있는 커밋 — openapi 파일은 두 커밋에서 SHA-256 같음을 확인).
- `contracts/shared/ga-agent-portal/{events/v1/**, api/v1/engine-internal.openapi.yaml}`, `UPSTREAM` = `hjryoo-ai/ga-agent-portal@f30d026ae51cb7885e2536b3afc1b5a038c3a140`.
- `contractChecksums`·`verifyContractChecksums`·`verifyUpstreamContract`를 `shared/*` 디렉터리마다로. 실패 메시지에 파일·두 해시(포털 K7과 같은 형식).
- 검증기: 엔진은 `json-schema-validator` 1.5.9(공시 모듈 시험). 포털은 3.0.6이다 — 판정 일치는 샘플 17 + 포털 `EventSchemaTest`의 위반 변형 묶음을 엔진 쪽에서도 돌려 같은 결과임을 본다. 판이 다른 검증기 두 개가 같은 판정을 내는지가 C1의 전제라 이 시험을 2단계에 둔다.

## 6. 완료 기준 → 시험·주입

| # | 시험 | 주입 |
|---|---|---|
| C1 | `RecordingOutboxStore`(시험 하네스 데코레이터, JVM 전역 집계) — 전체 스위트(빠른 티어 + 풀 빌드)에서 발행된 모든 이벤트를 포털 스키마로 검증, 위반 0, 타입별 건수를 빌드 산출물로 | I1 빌더에서 필수 필드 하나 제거 → 전역 검증기 실패 |
| C2 | `OutboxStoreContract`(인메모리·Oracle) + `OutboxSequencerOracleIT` — 미커밋 행 미부여, 커밋 뒤 다음 번호, 동시 부여 2건 직렬화 | I2 `FOR UPDATE` 제거 → 경합 IT 실패 |
| C3 | 발행 지점마다(표 12행) Oracle IT: 저장 뒤 강제 롤백 → `OUTBOX_EVENT` 0행 | I3 발행을 별도 트랜잭션으로 → 실패 |
| C4 | 단위 + 골든: `amount + limitCutAmt` = 게이트 전 지급액, Σ `DeferralScheduled.amount` = 분할 이연분 | I4 `amount`를 요청액으로 → 실패 |
| C5 | 마감·지급 단위 + 골든: REVERSED 원본·reversal 공존, `gross + clawbackOffset = gross_net` | I5 `gross`에 상태 필터 → 실패 |
| C6 | 전역 검증기의 `agentId` 집합 ∩ ORG id = ∅ | I6 ORG 라인 발행 → 실패 |
| C7 | `EventFeedControllerTest`(MockMvc standalone — 엔진 시험 관례) + `BootSmokeIT` 확장(Oracle·실제 보안 체인 401) + 응답이 `FeedResponse` 사본 스키마 통과 | I7 미부여 행 노출, I8 `tenantId` 누락 → 실패 |
| C8 | 이벤트 쪽 마감 불변식(P11)·지급 반복 무해 | I9 리졸버 우회 → 실패 |
| C9 | `verifyContractChecksums`·`verifyUpstreamContract`(디렉터리 둘)·`format` 0·IRI 원격 0 | I10 포털 사본 1바이트 + CHECKSUMS 재생성 → 업스트림 대조 실패 |
| C10 | 골든 스냅샷 `src/test/resources/outbox-golden/<case>.jsonl`(케이스 01·04·07·11 + 마감·지급 사이클) + 포털 A-1 대조(차이 표 예상 — P1, FY 1,890,000 vs 2,800,000) | I11 골든 금액 하나 변경 → 실패 |
| C11 | append-only 트리거(Oracle IT), 모듈 방향(P9), jqwik 0, E3.2 무손상 | I12 `commission-limit`에 `outbox` 의존 → 빌드 실패(순환) |

주입 **ID 단위 12건**(I1~I12).

## 7. 단계

1. 이 계획(승인 뒤 승인 보관 + 갱신 1커밋).
2. 계약 사본 재구성 + 들이기 + 검증기 판정 일치.
3. `commission-outbox`·`V105`(공통)·`V106`(Oracle, `IS JSON`·트리거)·`V107`(`PAYOUT_STATEMENT`, Q 승인 시)·저장소·부여기.
4. 데코레이터·리스너 포트·발행 지점(Q3이면 분급 취소 조립 별도 커밋).
5. 피드 API·잡 둘.
6. 공급자 증명(전역 검증기·골든·A-1 대조).
7. 마감 불변식(이벤트 측).
8. 문서(설계서 §6.8·§4.1·§10·§14, `db-migrations.md`, `CLAUDE.md`, `설계고찰.md`), 보고서, 태그, PR.

## 8. 포털에 넘길 것 (보고서에서 다시)

- 포털 샘플 06(`LIMIT_DEFER`)·02(FY 2,800,000) — 엔진 시드로 재현 불가(P1, 차이 표). 수정 후보.
- `LimitLedgerRebased.postingSeq` 최소값(P2).
- 조직장 v2 이벤트(ORG 수급자), `TenantSnapshot`(보존).
- 정정이 지급되지 않는 엔진 결함(P5) — 포털 화면의 "정정" 금액이 지급과 맞지 않을 수 있다.
