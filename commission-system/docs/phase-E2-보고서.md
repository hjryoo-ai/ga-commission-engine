# E2 보고서 — 잠정 명세·시뮬레이션·명세 조회·로컬 스택 (2026-10-17)

> 지시문 `docs/phase-E2-지시문.md`(`f55484b`), 계획 `docs/phase-E2-계획.md`(`4791307`), 승인 `docs/phase-E2-계획승인.md`(`41ab8f0` — 계획의 "승인 반영"이 본문에 우선). 브랜치 `work/e2`, PR #5. 설계서 v1.2.6. **`e2` 태그는 달지 않았고 병합하지 않았다**(수용 심사 뒤).

계약 사본의 `UPSTREAM` 두 줄:

```
contracts/shared/ga-agent-portal/UPSTREAM  hjryoo-ai/ga-agent-portal@78a593289c53e75910d6cee7a15c7307235ac864   (포털 계약 v1.3, PR #6 병합 커밋 — ② 전 재상향)
contracts/shared/ga-disclosure/UPSTREAM    hjryoo-ai/ga-disclosure@db82d5d4ca35de2e16ac1d6238fef5d39ae304a2      (변경 없음)
```

포털 사본은 E2 동안 두 번 올렸다: ⓪b = `3f876b6`(v1.2, `a92610b`) → v1.3 = `78a5932`(`4dcae09`).

## 1. 단계별 커밋·완료 기준 증거

| 순서 | 커밋 | 내용 | 완료 기준 증거(시험) |
|---|---|---|---|
| 승인 보관 | `41ab8f0` | 승인 원문 보관, 계획에 승인 반영, `설계고찰.md` §16 "E2 지시문 측 기록" | — |
| ⓪a | `a124621` | A-1 첫 판 `a1_portal_150000.jsonl`(10건) — 시나리오 값(`A1Scenario`)·실행기(`A1ScenarioRunner`)·단계 시계(`SteppingClock`)는 `commission-outbox` testFixtures. **이 커밋을 먼저 push하고 해시를 메인 세션에 보냈다** | `PortalA1StreamTest#첫_판은_파일과_바이트가_같다`·`#첫_판의_값`(한도 1,800,000·FY 945,000·시책 855,000+삭감 145,000·세액 59,400·순액 1,740,600·환수 −720,000(202702)·누적 1,080,000·단계 시각) |
| ⓪b | `a92610b` | 포털 사본 `3f876b6`(v1.2) — 샘플 경로를 디렉터리 포함으로(P17), 어휘 시험 | `PortalVocabularyTest`(3), 합의 시험 경로 |
| ④ | `b7ec748` | `ClosedMonthGuardedCalcStore`를 운영 조립 **바깥**에(발행 데코레이터를 감싼다), 골든·`OutboxEngine` 같은 순서. 설계서 §6.4·§11 E1-4 | C7 `AssemblyParityTest#계산_저장소의_바깥은_마감월_가드이고_골든_조립과_같다`, `ClosedMonthGuardAssemblyTest`(3 — 해석기 우회 마감월 insert 거부·미발행, 마감된 달 도래 처리 거부 = P10), 골든 24 + E1 골든 5 가드 경유 동일 |
| ①-준비 | `0f865f0` | 동작 무변경 추출: `PayoutMath.settle`, `ClawbackOffsetService.offsetLoop`·`previewConsume`, `DeferralReleaseService.releasable`, `WithholdingTaxPolicy` 빈 하나(P9) | 기존 시험 전부(지급 런 동작 동일) |
| ① | `753f325` | `StatementEstimator`(입력 = 읽기 함수 묶음 `EstimateReads`) + `EstimatePublisher` + 잡 `statementEstimateJob` + 빌더 `statementEstimated`. 설계서 §6.8 | C1 `DryRunNoWriteOracleIT#잠정명세_전후_업무표가_같다`, C2 `EstimateEqualsActualGoldenTest`(5 케이스, §3), C3 `DryRunLockOracleIT`, `StatementEstimatorTest`(4), `OracleStatementEstimateJobIT`(3) |
| ③ | `d595060` | 명세 조회 `GET /internal/v1/agents/{agentId}/statements/{ym}?mode=ESTIMATE\|CONFIRMED` | C6 `StatementQueryOracleIT#CONFIRMED는_발행된_이벤트와_같다`·`#마감_전_CONFIRMED와_마감월_ESTIMATE는_거부된다`, `StatementQueryControllerTest`(7 — 계약 오류 코드 전부) |
| v1.3 | `4dcae09` | 포털 사본 `78a5932` — A-1 대조를 **바이트 대조**로(`PortalA1ComparisonTest` → `PortalA1ByteIdentityTest`, P17), `StatementEstimated` R7 필드(`receivableOffset`·`carriedReceivable`·`payable`) | `PortalA1ByteIdentityTest#포털_A1_샘플은_엔진_첫_판과_바이트가_같다`, `verifyUpstreamContract`, 합의 시험(샘플 17건) |
| ② | `390a87b` | 시뮬레이션 2종 — 운영과 같은 Step·훅(`CalcSteps`)을 오버레이 저장소에 조립한 두 번째 계산기. `insurersWithRate`, 잠그지 않는 원장 `peek`, `ruleSnapshotId`. 설계서 §6.8·§11 E2-1·E2-2 | C1 `DryRunNoWriteOracleIT#시뮬레이션_명세조회_전후_업무표가_같고_아웃박스는_0행이다`, C3 `DryRunLockOracleIT#원장_잠금_중에도_잠정명세와_시뮬레이션은_기다리지_않는다`(①②③), C4 `RuleSnapshotIdTest`(3)·`SimulationDeterminismIT`·`DryRunSimulatorTest#같은_입력은_시계와_무관하게_같은_결과이고_generatedAt만_시계를_따른다`, C5 `SimulationContractTest`(5), C7 `AssemblyParityTest#시뮬레이션_파이프라인의_Step과_훅은_운영_계산기와_같다`, 금액 = 실제 처리 `DryRunSimulatorTest`(12), `RuleRepositoryContract#기준일에_유효_요율이_있는_보험사는_회차_무관_ACTIVE만_코드_순이다`(인메모리·Oracle) |
| ⓪ 두 번째 판 | `b6b65df` | `a1_portal_150000_e2.jsonl`(11건) = 첫 판 + 202702 잠정 명세 | C9 `PortalA1StreamTest#두_번째_판은_파일과_바이트가_같다`·`#첫_판은_두_번째_판의_앞부분이다` |
| ⑤ | `5342b52` | 로컬 스택 한 명령 + CI 잡 + Oracle 이미지 digest 고정(P7). ⑥ 넣지 않음 | C8 CI 잡 `로컬 스택(A-1 피드)`, `LocalStackSeedIT`(2) |
| 문서 | (이 커밋) | 설계서 v1.2.6(변경 이력·§10 E2), 이 보고서 | — |

`estimate_cycle_*.jsonl`(C2의 다섯 케이스 스트림, Q8)은 ①에서 커밋했고 v1.3에서 R7 필드로 다시 만들었다(`outbox-golden/estimate_cycle_01_기본` … `_05_분급_도래`).

C10: 전역 검증기 타입 13종(§5, `StatementEstimated` 포함, 위반 0), 골든 24 + E1 골든 5 동일, jqwik 0(빠른 티어 단계), CI 3단 + 로컬 스택 잡(§8), `PortalVocabularyTest`.

## 2. 주입 기록 (ID 단위 16건 — 각각 실행해 실패를 보고 제거, 커밋 본문에 같은 기록)

| ID | 주입 | 실패한 시험 | 커밋 |
|---|---|---|---|
| S1 | 산출기의 채권 읽기가 `consume`도 부름(실제 지급 calcId로) | `DryRunNoWriteOracleIT` — `CLAWBACK_RECEIVABLE`·`AGENT_SETTLEMENT*` 해시 다름. 첫 시도(payingCalcId 0)는 FK(ORA-02291)로 먼저 실패해서 해시 비교가 잡도록 다시 돌렸다 | `753f325` |
| S2 | `ruleSnapshotId`를 시계에서(`"RS-" + clock.millis`) | `DryRunSimulatorTest#같은_입력은_시계와_무관하게_같은_결과이고_generatedAt만_시계를_따른다` | `390a87b` |
| S3 | `requireOpen` 끔(마감월 허용) | `StatementEstimatorTest#마감월은_내지_않는다` | `753f325` |
| S4 | `CONFIRMED`가 REVERSED를 거름 | `StatementQueryControllerTest#CONFIRMED는_발행된_이벤트와_같다`(gross 1,890,000 기대 — reversal만 남음) | `d595060` |
| S5 | 운영 조립에서 가드 제거(`s.calcs()` 그대로) | `AssemblyParityTest#계산_저장소의_바깥은_마감월_가드이고_골든_조립과_같다` | `b7ec748` |
| S6 | 오버레이 `getOrCreate`가 운영(발행) 원장 저장소로 위임 | `DryRunNoWriteOracleIT#시뮬레이션_명세조회_전후_업무표가_같고_아웃박스는_0행이다`(`LIMIT_LEDGER` 해시), 인메모리 `DryRunSimulatorTest` 4/12(지문에 원장 `SIM-NEW-CONTRACT`) | `390a87b` |
| S7 | 시뮬레이터 배선이 잠그는 `LimitLedgerStore::find`(픽스처 + `SimulationConfig`) | `DryRunLockOracleIT#원장_잠금_중에도_잠정명세와_시뮬레이션은_기다리지_않는다` — `TimeoutException` | `390a87b` |
| S8 | 도래 분급 미리보기 끔 | `EstimateEqualsActualGoldenTest` 05_분급_도래(1/5) | `753f325` |
| S9 | 상계 미리보기를 0으로 | `EstimateEqualsActualGoldenTest` 02_채권_상계(1/5) | `753f325` |
| S10 | 두 번째 판에서 잠정 명세를 202608 마감 앞에 | `PortalA1StreamTest#첫_판은_두_번째_판의_앞부분이다`(꼬리 첫 건 = `LimitLedgerPosted`)·`#두_번째_판은_파일과_바이트가_같다` | `b6b65df` |
| S11 | 시드 SQL의 FY 요율 하나 변경 | (a) 7회차 0.15 → 0.16: `LocalStackSeedIT#시드의_룰_조회는_인메모리_표준_룰과_같다` (b) 신계약 7.0 → 7.1로 로컬 스택 전체: `up.sh verify` 종료 코드 1, "2번째 줄이 다르다"(amount 958,500 vs 945,000) | `5342b52` |
| S12 | dry-run 파이프라인에서 `PremiumRepriceStep` 제거 | `AssemblyParityTest#시뮬레이션_파이프라인의_Step과_훅은_운영_계산기와_같다` | `390a87b` |
| S13 | 산출기에 다른 세율 인스턴스(3% + 0%) | `EstimateEqualsActualGoldenTest` 01·02·03·05(4/5 — 04는 세액 0) | `753f325` |
| S14 | 빌더에서 `basisSeq` 제거 | `EstimateEqualsActualGoldenTest` 5/5 — 적재 시점 전역 검증기(required property 'basisSeq') | `753f325` |
| S15 | ③ `ESTIMATE`가 `StatementEstimated`를 발행 | `StatementQueryControllerTest#ESTIMATE는_산출기를_지금_실행한_값이고_발행하지_않는다`(8 → 9행), `DryRunNoWriteOracleIT#시뮬레이션_명세조회_…`(아웃박스 바이트) | `390a87b` |
| S16 | 엔진 열거 `ReceivableStatus`에 `DISPUTED` 추가 | `PortalVocabularyTest#채권_상태는_엔진_열거와_같다` | `b7ec748` |
| 추가 | 오버레이 원장이 사본을 만들지 않음(S 목록 밖) | `DryRunSimulatorTest` 3/12 — 인메모리 원 저장소의 원장이 바뀜 | `390a87b` |

제거 확인: 주입 전 파일을 scratchpad에 복사하고 주입 뒤 되돌린 다음 `cmp`로 비교했다(④에서 `git checkout -- file`이 커밋 전 작업을 지운 일이 있어 그 뒤로는 복사본 방식만 썼다).

## 3. C2 — 예상 = 실제 (케이스별 대조, `EstimateEqualsActualGoldenTest` → `build/reports/outbox-estimate/estimate-vs-actual.md`)

각 케이스: 잠정 명세 → 새 업무 입력(정책 이벤트·정정) 없이 마감 → 지급 → `StatementConfirmed` + `PayoutCompleted`의 같은 필드와 대조.

| 케이스 | 달 | gross | clawbackOffset | adjustment | withholdingTax | net | receivableOffset | carriedReceivable | payable |
|---|---|---|---|---|---|---|---|---|---|
| 01_기본 | 202608 | 1,890,000 = | 0 = | 0 = | 62,370 = | 1,827,630 = | 0 = | 0 = | 1,890,000 = |
| 02_채권_상계 | 202610 | 1,890,000 = | 0 = | 0 = | 18,711 = | 548,289 = | 1,323,000 = | 0 = | 567,000 = |
| 03_정정_포함 | 202609 | 1,890,000 = | 0 = | −45,000 = | 62,370 = | 1,827,630 = | 0 = | 0 = | 1,890,000 = |
| 04_음수_순액_채권화 | 202609 | 0 = | −1,323,000 = | 0 = | 0 = | 0 = | 0 = | 1,323,000 = | 0 = |
| 05_분급_도래 | 202801 | 378,000 = | 0 = | 0 = | 12,474 = | 365,526 = | 0 = | 0 = | 378,000 = |

(`=` = 잠정 명세와 확정·지급이 같다. 03의 `adjustment`는 `StatementConfirmed.adjustment`와 같고, 지급 런은 정정을 읽지 않는다 — §11 E1-2.)

## 4. C1 — 표별 전후 해시 (실 Oracle, `DryRunScenario` — 202608 계약·마감·지급, 202609 해약·마감·지급, 202610 새 계약, 2027 분급 계약)

①(`잠정명세_전후_업무표가_같다`, `build/reports/dry-run/c1-estimate.md`) — 로컬 실행:

| 표 | 전 | 후 |
|---|---|---|
| COMM_CALC | 9f518938e27197ad (13행) | 9f518938e27197ad (13행) |
| LIMIT_LEDGER | 133f6dfca94691f1 (3행) | 133f6dfca94691f1 (3행) |
| LIMIT_LEDGER_DTL | 74e62d133f413f0f (4행) | 74e62d133f413f0f (4행) |
| DEFERRAL_SCHEDULE | a592a8133dcce69b (3행) | a592a8133dcce69b (3행) |
| CLAWBACK_RECEIVABLE | 572723f98d9dd2be (1행) | 572723f98d9dd2be (1행) |
| CLAWBACK_OFFSET_HIST | e3b0c44298fc1c14 (0행) | e3b0c44298fc1c14 (0행) |
| AGENT_SETTLEMENT | 8b07095bc3d67253 (5행) | 8b07095bc3d67253 (5행) |
| AGENT_SETTLEMENT_CALC | dea8ac6d62cf708b (5행) | dea8ac6d62cf708b (5행) |
| PAYOUT_STATEMENT | 2a037ce47327ffe4 (5행) | 2a037ce47327ffe4 (5행) |
| POLICY_EVENT | 7b4eaf05e508f2ba (4행) | 7b4eaf05e508f2ba (4행) |
| SETTLE_CLOSE | 454764539265ebd3 (2행) | 454764539265ebd3 (2행) |
| ADJUSTMENT | e3b0c44298fc1c14 (0행) | e3b0c44298fc1c14 (0행) |
| OUTBOX_EVENT | 40행 | 41행(+1 StatementEstimated — 기존 40행은 바이트로 그대로) |

②③(`시뮬레이션_명세조회_전후_업무표가_같고_아웃박스는_0행이다`, `build/reports/dry-run/c1-simulation-statement.md` — 신계약 1·해약 2·감액 1 시뮬레이션 + `ESTIMATE` 조회 1) — 로컬 실행:

| 표 | 전 | 후 |
|---|---|---|
| COMM_CALC | ff24566e9dd7cba8 (13행) | ff24566e9dd7cba8 (13행) |
| LIMIT_LEDGER | ed9ead22f89b3bb7 (3행) | ed9ead22f89b3bb7 (3행) |
| LIMIT_LEDGER_DTL | dab63d0e79538a6f (4행) | dab63d0e79538a6f (4행) |
| DEFERRAL_SCHEDULE | 6ee202e951ba3c62 (3행) | 6ee202e951ba3c62 (3행) |
| CLAWBACK_RECEIVABLE | afd849d5360ed088 (1행) | afd849d5360ed088 (1행) |
| CLAWBACK_OFFSET_HIST | e3b0c44298fc1c14 (0행) | e3b0c44298fc1c14 (0행) |
| AGENT_SETTLEMENT | 3e7dc52f03995d2a (5행) | 3e7dc52f03995d2a (5행) |
| AGENT_SETTLEMENT_CALC | 885d93c08ab7ad00 (5행) | 885d93c08ab7ad00 (5행) |
| PAYOUT_STATEMENT | 0dfe92d3195fe128 (5행) | 0dfe92d3195fe128 (5행) |
| POLICY_EVENT | e4fe048bda140277 (4행) | e4fe048bda140277 (4행) |
| SETTLE_CLOSE | 454764539265ebd3 (2행) | 454764539265ebd3 (2행) |
| ADJUSTMENT | e3b0c44298fc1c14 (0행) | e3b0c44298fc1c14 (0행) |
| OUTBOX_EVENT | 20행 | 20행(바이트 동일, 새 행 0) |

두 표의 해시 값이 서로 다른 것은 실행마다 IDENTITY·시각 컬럼이 다르기 때문이다(같은 실행 안의 전후 비교만 뜻이 있다). 아웃박스 행 수도 앞선 시험들의 행이 남아 있는 공유 컨테이너라 다르다(append-only — 삭제 불가). 공허하지 않음: 해약 시뮬레이션의 환수 음수, 분급 계약 해약의 취소 예정 스케줄 비어 있지 않음, 신계약 오버레이 원장 한도 3,600,000을 같은 시험이 단언한다.

## 5. 테스트 건수 — CI 산출물 기준

PR run **39007290478**(head `5342b52`, 빠른 티어·풀 티어·no-docker·로컬 스택 전부 success)의 `test-reports` 산출물을 내려받아 결과 XML의 `<testsuite tests/skipped/failures/errors>`를 합산했다(전역 검증기 XML 제외). **10,473건, 실패 0, 오류 0, 스킵 0.** E1 10,412 대비 +61. 이 보고서 커밋은 문서만 바꾼다 — 그 run의 건수는 §8.

| 모듈(태스크) | 건수 | E1 대비 |
|---|---|---|
| commission-domain | 5,060 | 0 |
| commission-limit | 3,021 | 0 |
| commission-disclosure | 1,645 | 0 |
| commission-outbox | 170 | +27 (`DryRunSimulatorTest` 12, `EstimateEqualsActualGoldenTest` 5, `PortalA1StreamTest` 4, `PortalVocabularyTest` 3, `ClosedMonthGuardAssemblyTest` 3, `PortalA1ComparisonTest` 1 → `PortalA1ByteIdentityTest` 1) — E1 run 38967562103 산출물과 클래스별 대조 |
| commission-infra integrationTest (Oracle) | 169 | +12 (`DryRunNoWriteOracleIT` 2, `DryRunLockOracleIT` 1, `SimulationDeterminismIT` 1, `StatementQueryOracleIT` 2, `OracleStatementEstimateJobIT` 3, `LocalStackSeedIT` 2, `RuleRepositoryContract` +1) |
| commission-deferral | 111 | 0 |
| commission-api | 70 | +12 (`StatementQueryControllerTest` 7, `SimulationContractTest` 5) |
| commission-rule | 67 | +1 (`RuleRepositoryContract` 인메모리) |
| commission-settlement | 63 | +7 (`StatementEstimatorTest` 4, `RuleSnapshotIdTest` 3) |
| commission-calc | 32 | 0 |
| commission-infra test (H2) | 19 | 0 |
| commission-app | 14 | +2 (`AssemblyParityTest` 가드·시뮬레이션 파이프라인) |
| commission-clawback | 12 | 0 |
| commission-batch | 8 | 0 |
| commission-inbound | 7 | 0 |
| commission-shadow | 3 | 0 |
| commission-recon | 2 | 0 |

no-docker 잡(같은 run): `gradle exit code: 1`, `153 tests completed, 153 failed`, 결과 파일 39개 전부 실패, 스킵 0(Docker 없이는 파라미터 시험 일부가 클래스 초기화에서 실패해 169보다 적게 센다).

**전역 검증기 집계**(같은 산출물의 `*/build/test-results/outbox-validation/*.xml`, 위반 0 — 13타입 전부, `StatementEstimated` 포함):

| 타입 | outbox test | infra integrationTest | api test | batch test | 계 |
|---|---|---|---|---|---|
| CommissionCalculated | 187 | 50 | 31 | — | 268 |
| LimitLedgerPosted | 163 | 48 | 31 | — | 242 |
| DeferralScheduled | 70 | 36 | — | — | 106 |
| LimitLedgerOpened | 50 | 36 | 19 | — | 105 |
| StatementConfirmed | 34 | 24 | 7 | — | 65 |
| PayoutCompleted | 28 | 22 | 1 | — | 51 |
| PolicyEventObserved | 29 | 14 | — | 5 | 48 |
| DeferralStatusChanged | 14 | 8 | — | — | 22 |
| ClawbackReceivableUpdated | 7 | 11 | — | — | 18 |
| StatementEstimated | 8 | 4 | — | — | 12 |
| AdjustmentPosted | 4 | 2 | — | — | 6 |
| AgentSnapshot | 1 | 2 | — | 1 | 4 |
| LimitLedgerRebased | 2 | 2 | — | — | 4 |
| **계** | **597** | **259** | **89** | **6** | **951** |

api 시험이 이번에 집계에 들어온 것은 ③·② 시험이 `OutboxEngine`(검증 데코레이터 포함)으로 상태를 만들기 때문이다. 시뮬레이션은 아무것도 적재하지 않으므로 이 표에 시뮬레이션 몫은 0이다(C1).

## 6. ⓪ A-1 두 판

| 판 | 파일 | 건수 | 바이트 | SHA-256 |
|---|---|---|---|---|
| 첫 판 | `commission-outbox/src/test/resources/outbox-golden/a1_portal_150000.jsonl` | 10 | 4,517 | `25929e7f9908227cd943f75da02b5e0313483a1e05731d785cbbe66477abe718` |
| 두 번째 판 | `…/a1_portal_150000_e2.jsonl` | 11 | 4,948 | `e9a03d37c513e591e7623d95bce6bc49ef2269e3c1f1336d369be2cc1eeb751a` |

두 번째 판의 앞 4,517바이트 = 첫 판(시험 `#첫_판은_두_번째_판의_앞부분이다`). 덧붙은 한 건: `StatementEstimated` A-1001/202702 — gross 0, clawbackOffset −720,000, withholdingTax 0, net 0, receivableOffset 0, carriedReceivable 720,000, payable 0, basisSeq 10(첫 판 10건 — 하한), asOf = occurredAt = 2027-02-16T02:10:00+09:00. 포털 `samples/a1/a1_portal_150000.jsonl`(@`78a5932`)은 첫 판과 바이트가 같다(`PortalA1ByteIdentityTest`).

## 7. ⑤ 로컬 스택 — 실행 명령과 소요

```bash
cd commission-system
localstack/up.sh          # 37.8 s (macOS, 이미지 캐시됨): Oracle healthy → Flyway 22개 → 시드 68문장 → monthCloseJob·payoutRunJob·statementEstimateJob COMPLETED → OUTBOX_EVENT 11행 → 앱 :8080 준비
localstack/up.sh verify   # [verify] 피드 = a1_portal_150000_e2.jsonl (11건, 바이트 동일)
localstack/up.sh down
```

토큰 파일 `localstack/.state/engine-token`(권한 600) — 포털 `e2e/engine-feed/`가 읽는다. CI 잡 소요는 §8(2분 22초).

## 8. CI (엔진, `gh run list --branch work/e2`·`gh run view`로 직접 조회)

| 커밋 | push run | pull_request run |
|---|---|---|
| `4791307` | 38999639217 success | — |
| `41ab8f0` | 39002595129 success | — |
| `a124621` | 39002749662 success | — |
| `a92610b` | 39002942106 success | — |
| `b7ec748` | 39003163285 success | — |
| `0f865f0` | 39003345249 success | — |
| `753f325` | 39004038594 success | — |
| `d595060` | 39004366149 success | — |
| `4dcae09` | 39004583867 success | — |
| `390a87b` | 39006292399 success | — |
| `b6b65df` | 39006347508 success | — |
| `5342b52` | 39007271982 success | **39007290478 success**(§5의 산출물) |

push는 빠른 티어 + no-docker(풀 티어·로컬 스택은 PR·main만). PR #5는 `5342b52`에서 초안으로 열었다(풀 티어·로컬 스택을 일찍 돌리려고).

**로컬 스택 잡**(run 39007290478, 2분 22초): Oracle 기동·healthy 53초 → 시나리오(Flyway·시드 68문장·잡 셋 COMPLETED, OUTBOX_EVENT 11행) 49초 → 앱 준비 17초 → `[verify] 피드 = a1_portal_150000_e2.jsonl (11건, 바이트 동일)`. 풀 티어 2분 42초와 병렬.

이 보고서 커밋의 CI는 PR에서 확인한 뒤 PR을 리뷰 준비로 바꾼다.

## 9. 계획·승인과 다르게 한 점

1. **`CalcSteps`는 `commission-settlement`에 두었다**(계획: `commission-calc`). 계산 모듈은 한도·환수·분급 Step을 모른다(그 모듈들이 계산 모듈을 의존 — 순환). 셋을 모두 의존하는 정산 모듈이 가장 아래다.
2. **가드 동작 시험은 인메모리 시험 `ClosedMonthGuardAssemblyTest`**(계획: `ClosedMonthGuardAssemblyIT`). 가드는 저장소 포트 데코레이터라 Oracle 없이도 같은 조립(`OutboxEngine` = 운영 순서)으로 증명된다. 운영 빈 순서는 `AssemblyParityTest`.
3. **③ 그 달 대상이 아닌 알려진 설계사는 200 + 0원 명세**(계획: 404). 계약 v1.3의 404는 `AGENT_NOT_FOUND`(엔진에 없는 설계사)와 `STATEMENT_NOT_CONFIRMED`(마감 전)뿐이다.
4. **④ 커밋 본문의 시험 건수 10,422는 틀렸다 — 10,421이다**(그 시점 로컬 빌드 XML 합). 커밋은 고치지 않았다(공개 이력).
5. 승인 §4의 "`설계고찰.md` §16"은 §16 안의 하위 절 "E2 지시문 측 기록"으로 넣었다(§16이 이미 Phase별 기록 절이다).
6. **포털 계약 YAML을 YAML 1.2 불리언으로 읽는다**(시험 픽스처 `PortalContract` — `YAMLParser.Feature.PARSE_BOOLEAN_LIKE_WORDS_AS_STRINGS`). 기본 YAML 1.1 해석은 `fyCommissionByInstallment`의 `required: [no, amount]`를 `[false, amount]`로 읽어 `SimulationContractTest`의 모든 `NewContractResult`가 실패했다("required property 'false' not found"). OpenAPI 3.1은 YAML 1.2를 권장한다 — 계약은 고치지 않았고 질문으로 넘긴다(§12-1). 그 전에는 이 스키마를 검증에 쓰지 않아 드러나지 않았다.
7. **⑥ 스냅샷 부트스트랩을 넣지 않았다**(승인 Q5 "비용이 커지면 빼고 보고"). 열린 분급·채권 전체를 읽는 포트가 없어(분급은 도래월·계약·원본 계산 단위, 채권은 설계사 단위) 저장소 셋(인메모리·Oracle·데코레이터)에 포트를 더해야 하고, 합성 사실의 `occurredAt`이 업무 시각이 아니다. 지금 소비자(포털 Phase 2·로컬 스택)는 `afterSeq=0` 재생으로 충분하다. 설계서 §6.8·§11 E2-3, 포털 질문 §12-3.
8. **버전 카탈로그 `gradle/libs.versions.toml`을 새로 만들었다**(계획 P7은 "카탈로그의 digest"라고만 — 엔진에는 카탈로그가 없었다). 항목은 Oracle 이미지 참조 하나뿐(라이브러리는 Boot BOM). IT 둘은 시스템 속성 `engine.oracleImage`(기본값 없음, 태스크 입력 선언)로 받는다.
9. **로컬 스택의 마감·지급·잠정 명세는 운영 잡으로**(계획: "계산 → 마감 → 지급 → 해약 → 잠정 명세 순으로 돌린다" — 수단 미정). `monthCloseJob`(마감 리포트 게이트 포함)·`payoutRunJob`·`statementEstimateJob`을 `BatchRequestRunner`로. 계산만 서비스 직접 호출(운영 경로 없음 — E1 P12). 시나리오 컨텍스트는 보안 체인 때문에 서블릿까지 띄운다(포트 0).
10. **C1의 ②③은 별도 시험 메서드**(`시뮬레이션_명세조회_전후_업무표가_같고_아웃박스는_0행이다`) — ①은 새 행 1건(`StatementEstimated`), ②③은 0행이라 단언이 다르다. C3은 계획의 이름 하나(`원장_잠금_중에도_잠정명세와_시뮬레이션은_기다리지_않는다`)에 ①②③.
11. **`SimulationDeterminismIT`는 결과 레코드의 Jackson 직렬화 바이트를 비교한다**(계획: "응답 바이트"). 컨트롤러 직렬화는 레코드의 순수 함수(고정 필드 순서)이고 그 모양은 `SimulationContractTest`가 계약 스키마로 본다.
12. 시뮬레이션 응답 필드의 정의는 계약에 없던 부분을 이렇게 정했다(설계서 §6.8 표, 포털 질문 §12-4): `deferral.firstYearPayout` = Σ`fyCommissionByInstallment` + Σ`incentives.amount`, `schedule`은 `dueYm`별 합산, `incentives`는 시책 코드별 합산, `receivableRemaining` = 열린 채권 잔액 + max(0, −after), `INSURER_AMBIGUOUS`는 후보 0곳에도, `clawbackAmount`는 `CommissionCalculated`와 같은 부호라 **부활(REINSTATE) 재지급이면 양수**(계약 설명 "≤ 0"과 다르다).

## 10. 찾은 결함·사실

- 포털 계약 YAML의 `no` 키(§9-6) — YAML 1.1 해석기(PyYAML, Jackson 기본값)에서 불리언 false가 된다.
- 기존 `/api/commissions/simulate`는 파이프라인이 아니다(P4) — 새 시뮬레이터와 숫자가 다를 수 있다(§11 E2-1, 건드리지 않음).
- 원천세율은 코드 상수(P9) — 이번에는 같은 빈 하나로만(§11 E2-2).
- Flyway가 Oracle 23.26을 "테스트되지 않은 버전"으로 경고한다(로컬 스택 로그, Boot BOM의 Flyway). 동작은 정상 — Boot BOM 관리라 버전은 그대로 뒀다.

## 11. 지시문 측 기록

승인 §4 그대로 `설계고찰.md` §16에 넣었다(P1~P17 — 되돌린 결정 0건).

## 12. 포털에 넘길 질문

1. **`engine-internal.openapi.yaml`의 `no` 키를 따옴표로**(`'no'`) — YAML 1.1 해석기에서 `required: [no, amount]`가 `[false, amount]`가 된다(엔진은 1.2로 읽게 고쳤다, §9-6).
2. **`StatementEstimated` 샘플 교체** — 계약의 `contract-only` 샘플 대신 엔진 실 스트림(두 번째 판의 마지막 줄, 또는 `estimate_cycle_*.jsonl`)을 쓸지. 두 번째 판은 포털 `a1/`에 바이트로 들일 수 있다(첫 판이 앞부분).
3. **⑥ → 계약 v2 `TenantSnapshot`** — 아웃박스가 빈 채로 업무 데이터가 있는 테넌트(현재 한도 원장·열린 분급·열린 채권·열린 달 계산)와 지난 달 명세 이력은 v1 이벤트로 표현할 수 없다(합성 `occurredAt`, 라인 없는 `LimitLedgerPosted`). 스냅샷 전용 타입을 v2 항목으로.
4. **시뮬레이션 응답 정의 확인**(§9-12): `firstYearPayout`·`schedule` 합산 단위·`incentives` 코드별 합산·`receivableRemaining` 공식, `INSURER_AMBIGUOUS`의 0곳, `clawbackAmount`의 부활 양수(계약 설명 "≤ 0" 수정 또는 REINSTATE를 별도 필드로).

## 13. 엔진 쪽 질문

1. 기존 `/api/commissions/simulate`(§11 E2-1)를 새 시뮬레이터로 바꿀 Phase(E3 이후 후보)와 그 소비자 확인.
2. 로컬 스택 CI 잡을 풀 티어처럼 PR·main에서만 둘지, 또는 push에도(2분 22초 — §8) 둘지.
