# E1 보고서 — 아웃박스·이벤트 피드 (2026-10-17)

> 지시문 `docs/phase-E1-지시문.md`(`3e369e7`), 계획 `docs/phase-E1-계획.md`(`3fba123`), 승인 `docs/phase-E1-계획승인.md`(`46e1de6` — 계획의 "승인 반영"이 본문에 우선). 브랜치 `work/e1`, PR #4. 설계서 v1.2.5. **`e1` 태그는 달지 않았고 병합하지 않았다**(수용 심사 뒤).

계약 사본의 `UPSTREAM` 두 줄:

```
contracts/shared/ga-agent-portal/UPSTREAM  hjryoo-ai/ga-agent-portal@f54d008dd220b529804689aa1e89f8fc6bbc6d4a   (포털 계약 v1.1.1 병합 커밋)
contracts/shared/ga-disclosure/UPSTREAM    hjryoo-ai/ga-disclosure@db82d5d4ca35de2e16ac1d6238fef5d39ae304a2      (engine-disclosure 1.2.1 + envelope-core)
```

## 1. 단계별 커밋·완료 기준 증거

| 단계 | 커밋 | 내용 |
|---|---|---|
| 0 | `3e369e7` `3fba123` `46e1de6` | 지시문 보관, 계획, 승인 보관·계획 갱신 |
| 2 | `83dd966` | 계약 사본 업스트림별 디렉터리(`contracts/shared/{ga-disclosure,ga-agent-portal}/`) + `CHECKSUMS`·`UPSTREAM` 디렉터리마다 + 포털 계약 검증기(IRI → 저장소 사본만) + 검증기 판정 일치 77건 |
| 3 | `0342e3a` | `commission-outbox` 모듈, V105(공통)·V106(Oracle — `IS JSON`·append-only 트리거)·V107(`PAYOUT_STATEMENT`), 인메모리·H2·Oracle `OutboxStore`, 순번 부여기 |
| 4 | `eca91c4` | 발행 지점 — 저장소 데코레이터 6, 리스너 포트 2, 빌더, 전역 검증기, C3 Oracle IT |
| 4(Q3) | `b3aefe9` | 분급 취소 리스너·reversal 훅을 앱 조립에 + 조립 일치 시험 |
| 5 | `3e8ddcc` | 피드 API, `outboxSequenceJob`, `outboxAgentSnapshotJob` |
| 6 | `8393feb` | 이벤트 골든(24건 업무 불변 + 스냅샷 5), 포털 A-1 대조 |
| 7 | `9b70f53` | 이벤트 쪽 마감 불변식, 지급 반복 무해 |
| 8 | `0b43ad4` | 모듈 경계 스캔, 설계서 §4.1·§6.1·§6.8·§10·§11·§12, `설계고찰.md` §16 |
| 8 | (이 커밋) | 이 보고서 |

| # | 기준 | 증거(클래스#메서드) |
|---|---|---|
| C1 | 발행된 모든 이벤트가 포털 Envelope·payload 스키마 통과 | 시험 픽스처 `ValidatingOutboxStore`(적재 시점 검증, 위반 = `AssertionError`) + `OutboxValidationReport`(태스크별 집계 XML, §4). 엔진 검증기(networknt 1.5.9)와 포털 검증기(3.0.6) 판정 일치: `PortalContractAgreementTest`(샘플 17 + 포털 `EventSchemaTest` 위반 변형, 77건) |
| C2 | 순번 갭 0·커밋된 행만·겹친 부여 직렬화 | `OutboxStoreContract`(인메모리·H2·Oracle 각 8), `OutboxSequencerOracleIT#미커밋_행은_부여되지_않고_커밋_뒤_다음_번호를_받는다`·`#겹친_부여는_잠금에서_직렬화된다`, `OutboxAppendOnlyIT`(11) |
| C3 | 업무 롤백 → 이벤트 0 | `OutboxPublicationOracleIT` 9건 — 발행 지점 표 12행 전부(트랜잭션 안에서는 적재돼 보였음을 같이 단언, 커밋하면 남는 대조) |
| C4 | `amount + limitCutAmt` = 요청액, 즉시분 + Σ `DeferralScheduled` = 분할 전 총액 | `OutboxPublicationTest#한도_삭감은_지급액과_삭감액으로_나뉘어_합이_요청액이다`·`#분급_분할과_도래와_해약_취소`, `PortalPayloadsTest#계산_이벤트는_엔진_값을_그대로_옮긴다`, 골든 07 스냅샷 |
| C5 | 전 상태 합산, `gross + clawbackOffset = gross_net` | `PortalPayloadsTest#집계는_상태와_무관하게_유형으로만_나눈다`·`#지급_합산_정의가_어긋나면_발행하지_않는다`, `OutboxPublicationTest#마감_전_정정이_있어도_명세_집계는_순액과_같다`·`#마감과_지급과_채권_사이클` |
| C6 | ORG 식별자는 어떤 이벤트에도 없다 | `OutboxPublicationTest#마감과_지급과_채권_사이클`(전 이벤트 `agentId` = {A-1001}, ORG {T1,B1,H1}과 교집합 0), `PortalPayloadsTest#ORG_수급자는_빌더가_거부한다`, 전역 집계 `agentIds count=1`(§4) |
| C7 | 피드 규약 | `EventFeedControllerTest`(14 — MockMvc standalone, 응답을 포털 `FeedResponse`·`Problem` 사본으로 검증: 빈 피드, 부여 상한 너머 행 비노출·다음 호출에서 이어진 번호, `limit`·기본 200, 422, 400 9종, 테넌트 형식), `BootSmokeIT#이벤트_피드_E2E`(실 Oracle·실 보안 체인: 401 3종, 갭 0, 스키마, 422, 400, AgentSnapshot 잡) |
| C8 | 이벤트 쪽 마감 불변식, 지급 반복 무해 | `CloseInvariantEventTest`(3), `BootSmokeIT#이벤트_피드_E2E`(운영 리졸버 경로 — 마감월 날짜의 늦은 신계약이 202609, 전체 피드 `CloseInvariant` 위반 0) |
| C9 | 사본 바이트 동일·UPSTREAM·원격 조회 0 | `verifyContractChecksums`(check), `verifyUpstreamContract`(CI 빠른 티어 — 파일마다 `== <repo>@<commit> (<sha256>)`), `PortalContract`의 IRI 로더(그 밖의 http(s)는 예외) |
| C10 | 골든 스냅샷·A-1 대조 | `OutboxGoldenTest`(24 케이스 + 사이클 = 25), `PortalA1ComparisonTest#A1_대조`(§6) |
| C11 | append-only·모듈 방향·jqwik 0·E3.2 무손상 | `OutboxAppendOnlyIT`, `OutboxBoundaryTest`(3), Gradle 순환(I12), CI jqwik 부재 단계, 기존 시험 전부 통과(§3) |
| Q3 | 운영 조립 = 골든 조립 | `AssemblyParityTest`(2) |

## 2. 주입 기록 (ID 단위 13건, 각각 실행해 실패를 보고 제거 — 커밋 본문에 같은 기록)

| ID | 주입 | 관찰한 실패 | 커밋 |
|---|---|---|---|
| I1 | `CommissionCalculated` 빌더에서 `policyNo` 제거 | 아웃박스 시험 12건 실패 — 9건은 전역 검증기(`AssertionError … required property 'policyNo' not found`), 3건은 payload 스키마 단언 | `eca91c4` |
| I2 | 부여기 `OUTBOX_SEQ … FOR UPDATE` 제거 | `OutboxSequencerOracleIT#겹친_부여는_잠금에서_직렬화된다` 실패(`IllegalStateException` 순번 부여 경합 event_no=1) | `0342e3a` |
| I3 | Oracle 아웃박스 적재를 `REQUIRES_NEW` 트랜잭션으로 | `OutboxPublicationOracleIT` 8/8 실패(`롤백 뒤 OUTBOX_EVENT 행 수 expected: 3L but was: 4L`) | `eca91c4` |
| I4 | `amount` = `calc_amount + limit_cut_amt` | `한도_삭감은_…_요청액이다`(3,000,000 기대, 4,290,000), `계산_이벤트는_엔진_값을_그대로_옮긴다` 실패 | `eca91c4` |
| I5 | `gross`에서 REVERSED 제외(상태 필터) | `집계는_상태와_무관하게_…`(1,701,000 기대, −189,000), `마감_전_정정이_…`(빌더 단언 `gross + clawbackOffset(0) ≠ gross_net(1890000)`) | `eca91c4` |
| I6 | ORG 필터 제거(데코레이터·빌더) | 4건 실패, C6: `agentIds [A-1001, H1, T1, B1]` | `eca91c4` |
| I7 | 피드 읽기 SQL `NVL(seq, event_no) > afterSeq`(미부여 행 노출) | H2 `H2OutboxStoreTest`·Oracle `OracleOutboxStoreIT` `#적재한_행은_순번이_붙기_전에는_읽히지_않는다` 실패(`StoredEvent[eventNo=11, seq=null…]`) | `3e8ddcc` |
| I8 | 피드 응답에서 `tenantId` 제거 | `EventFeedControllerTest` 3건 실패(`FeedResponse`: `required property 'tenantId' not found`) | `3e8ddcc` |
| I9 | 앱 `closeYmResolver`를 `CloseYmResolver.byEventDate()`로(리졸버 우회) | `BootSmokeIT#이벤트_피드_E2E` 실패(`CloseInvariant`: `seq 7 CommissionCalculated → 이미 확정된 A-1001:202608`) | `9b70f53` |
| I10 | 포털 사본 `PayoutCompleted.v1.schema.json` 마지막 바이트 변경 + CHECKSUMS 재생성 | `verifyContractChecksums` 통과, `verifyUpstreamContract` 실패(사본 `fdfd5551…`, 업스트림 `edafd701…`) | `83dd966` |
| I11 | 골든 스냅샷 07의 금액 하나(669500 → 669400) | `OutboxGoldenTest > 07_한도_임박_초과_삭감` 실패. 스냅샷 디렉터리가 선언된 입력이라 태스크가 다시 돌았다 | `8393feb` |
| I12 | `commission-limit`에 `implementation(project(":commission-outbox"))` | Gradle `Circular dependency between the following tasks: :commission-clawback:compileJava — :commission-limit:compileJava …` | `0b43ad4` |
| I13 | 앱 `RevisionService` 훅에서 `DeferralReversalHook` 제거 | `AssemblyParityTest#정정_서비스의_reversal_훅은_골든_조립과_같다` 실패(`expected [LimitReversalHook, DeferralReversalHook] but was [LimitReversalHook]`) | `b3aefe9` |

ID 밖 확인 1건(`0b43ad4`): `commission-calc` 원천에 `ga.comm.outbox.OutboxStore` 참조(주석) → `OutboxBoundaryTest#업무_모듈_main은_아웃박스를_모른다` 실패, 입력 선언으로 재실행됨. (import는 컴파일 자체가 실패한다 — calc는 outbox를 볼 수 없다.)

첫 시도에 통과한 주입은 없었다.

## 3. 테스트 건수 — CI 산출물 기준

PR run **38967562103**(head `0b43ad4`, 빠른 티어·풀 티어·no-docker 전부 success)의 `test-reports` 산출물을 내려받아 결과 XML의 `<testsuite tests/skipped/failures/errors>`를 합산했다(전역 검증기 XML 제외). **10,412건, 실패 0, 오류 0, 스킵 0.** E3.2 10,200 대비 +212.

| 모듈(태스크) | 건수 | E3.2 대비 |
|---|---|---|
| commission-domain | 5,060 | 0 |
| commission-limit | 3,021 | 0 |
| commission-disclosure | 1,645 | 0 |
| commission-infra integrationTest (Oracle) | 157 | +36 (아웃박스 저장소 8·부여기 경합 2·append-only 11·지급명세 4·발행 지점 C3 9·정정 조회 1·마감 확정 시각 1) |
| commission-outbox (신규) | 143 | +143 |
| commission-deferral | 111 | 0 |
| commission-rule | 66 | 0 |
| commission-api | 58 | +14 (`EventFeedControllerTest`) |
| commission-settlement | 56 | +4 (확정 시각 계약 1·정정 조회 계약 1·지급명세 계약 2) |
| commission-calc | 32 | 0 |
| commission-infra test (H2) | 19 | +10 (아웃박스 8·지급명세 2) |
| commission-app | 12 | +3 (`AssemblyParityTest` 2, `BootSmokeIT#이벤트_피드_E2E`) |
| commission-clawback | 12 | 0 |
| commission-batch | 8 | +2 (`OutboxJobsFlowTest`) |
| commission-inbound | 7 | 0 |
| commission-shadow | 3 | 0 |
| commission-recon | 2 | 0 |

no-docker 잡(같은 run): `gradle exit code: 1`, `141 tests completed, 141 failed`, 결과 파일 33개 전부 실패, 스킵 0.

## 4. 전역 검증기 집계 (같은 산출물의 `*/build/test-results/outbox-validation/*.xml`, 위반 0)

| 타입 | outbox test | infra integrationTest | batch test | 계 |
|---|---|---|---|---|
| CommissionCalculated | 120 | 14 | — | 134 |
| LimitLedgerPosted | 97 | 12 | — | 109 |
| LimitLedgerOpened | 28 | 9 | — | 37 |
| DeferralScheduled | 28 | 9 | — | 37 |
| PolicyEventObserved | 17 | 5 | 5 | 27 |
| DeferralStatusChanged | 10 | 8 | — | 18 |
| StatementConfirmed | 13 | 6 | — | 19 |
| PayoutCompleted | 9 | 4 | — | 13 |
| ClawbackReceivableUpdated | 4 | 2 | — | 6 |
| AdjustmentPosted | 3 | 2 | — | 5 |
| LimitLedgerRebased | 2 | 2 | — | 4 |
| AgentSnapshot | 1 | 2 | 1 | 4 |
| **계** | **332** | **75** | **6** | **413** |

`agentIds` = 1개(A-1001) — 모든 태스크. 엔진이 내는 12타입 전부가 검증됐다(`StatementEstimated`는 E2). 건수는 **검증한 적재** 수다 — C3 IT의 롤백된 적재도 적재 시점에 검증되므로 들어 있다. 앱 시험(`BootSmokeIT`)은 운영 조립(검증 데코레이터 없음)이라 이 집계에 없고, 대신 피드 응답 전체를 `FeedResponse` 스키마로 검증한다. api 시험도 같다.

## 5. 발행 지점 표 (설계서 §6.8)

| 이벤트 | 클래스(발행 지점) | 트랜잭션 |
|---|---|---|
| CommissionCalculated | `OutboxCommCalcStore.insert` — AGENT만 | 계산 `inTx`·`recalcJob` 항목 tx, 정정 reversal tx, 분급 도래(마감 closeStep tx·`deferralReleaseJob`) |
| PolicyEventObserved | `OutboxPolicyEventStore.upsertByKey` — 새 행·매핑 유형 | 수신(`process()`) tx |
| LimitLedgerOpened | `OutboxLimitLedgerStore.getOrCreate` — 직전 `find` 빔 + 개설 이벤트 없음 | 계산 tx(원장 `FOR UPDATE`) |
| LimitLedgerPosted | `OutboxLimitLedgerStore.save` — 새 꼬리 전기마다 | 그 `save`의 tx(게이트 포스터·환수 차감·reversal 훅) |
| LimitLedgerRebased | `OutboxLimitLedgerStore.save` — `limit_amount` 변화 | 재산정 tx |
| DeferralScheduled | `OutboxDeferralScheduleStore.createAll` | 계산 tx |
| DeferralStatusChanged | `OutboxDeferralScheduleStore.replace(entry, reason)` | 도래·해약·정정 tx |
| ClawbackReceivableUpdated | `OutboxClawbackReceivableStore.create`·`save` | 지급 항목 tx |
| AdjustmentPosted | `OutboxAdjustmentStore.create` — 대상이 AGENT | 호출자 tx(지금은 시험뿐) |
| StatementConfirmed | `StatementPublisher`(`MonthCloseListener`) | 마감 tx(④ 뒤) |
| PayoutCompleted | `PayoutPublisher`(`PayoutListener`) | 지급 항목 tx(`PAYOUT_STATEMENT` 저장 뒤) |
| AgentSnapshot | `AgentSnapshotPublisher` ← `outboxAgentSnapshotJob` | 설계사마다 항목 tx |

조립은 `PublishingStores.wrap` 한 곳(앱 `PersistenceConfig`·Oracle IT·아웃박스 시험 `OutboxEngine`).

## 6. 이벤트 골든·포털 A-1 대조

**스냅샷 파일**(`commission-outbox/src/test/resources/outbox-golden/`, 고정 시계·순차 eventId라 `eventId`·`occurredAt`·`seq`까지 바이트 대조):

| 파일 | 이벤트 수 |
|---|---|
| `01_신계약_기본.jsonl` | 1 |
| `04_시책_라인_편입.jsonl` | 2 |
| `07_한도_임박_초과_삭감.jsonl` | 9 |
| `11_해약_7회차_환수.jsonl` | 18 |
| `cycle_마감_지급_채권_정정.jsonl` | 19 |

골든셋 24건 전부를 데코레이터를 끼운 엔진으로 다시 돌려 비교기 불일치 0(데코레이터가 업무 결과를 바꾸지 않는다).

**A-1 차이 표**(`PortalA1ComparisonTest`가 `build/reports/outbox-a1/a1-diff.md`로 쓴 것 — 같은 입력: 월납 300,000·2026-08-01 체결·시책 1,000,000·2026-09 마감·지급·2027-02-15 해약 7회차):

| seq | 샘플 | 샘플 값 | 엔진 | 이유 |
|---|---|---|---|---|
| 1 | LimitLedgerOpened | limitAmount 3,600,000 | 같음 | — |
| 2 | CommissionCalculated FY_COMM | 2,800,000 | 1,890,000 | 엔진 시드 FY 요율 7.0 × 지급률 0.9(샘플 rate 9.333333·payoutRate 1) |
| 3 | LimitLedgerPosted | 2,800,000, accum 2,800,000 | 1,890,000, accum 1,890,000 | 위 |
| 4 | CommissionCalculated INCENTIVE | 800,000, 삭감 200,000 DEFER_AFTER_FY | 1,000,000, 삭감 0 | 1,890,000 + 1,000,000 < 한도 3,600,000 |
| 5 | LimitLedgerPosted | 800,000, accum 3,600,000 | 1,000,000, accum 2,890,000 | 위 |
| 6 | DeferralScheduled LIMIT_DEFER | 200,000 | 없음 | P1 — DEFER_AFTER_FY는 이연 스케줄을 만들지 않는다(§11 E1-1) |
| 7 | StatementEstimated | — | 없음 | E2 범위 |
| 8 | StatementConfirmed | gross 3,600,000, net 3,481,200 | gross 2,890,000, clawbackOffset 0(세액·순액 없음) | 금액은 위 차이, 샘플은 마감 시점에 참이 아닌 세액·순액을 싣는다 |
| 9 | PayoutCompleted | paidAmount 3,481,200 | paidAmount 2,794,630(+ runSeq·gross·…·net) | 위 |
| 10 | PolicyEventObserved CANCEL | — | 같음 | — |
| 11 | CommissionCalculated CLAWBACK | −1,200,000 | −1,156,000 | 엔진 환수율표 7~12회차 40% × 2,890,000 |
| 12 | LimitLedgerPosted | −1,200,000, accum 2,400,000 | −1,156,000, accum 1,734,000 | 위 |
| 13 | DeferralStatusChanged | CANCELLED, POLICY_CANCELLED | 없음 | 취소할 LIMIT_DEFER 스케줄이 없다. 사유 어휘도 다르다(엔진 `POLICY_TERMINATED`) |

**타입 순서 결과**: 엔진이 내지 않는 타입(이유 있는 셋 — StatementEstimated, DeferralScheduled LIMIT_DEFER, 그 취소)을 샘플에서 빼면 타입 다중집합이 같고 사실 단위 순서(개설 → 계산·전기 → 확정 → 지급 → 해약 관측 → 환수 계산 → 환수 전기)도 같다. **다른 곳은 한 곳** — 샘플은 FY와 시책을 두 계산으로 나눠 `계산·전기·계산·전기`, 엔진은 한 계산의 두 라인을 `계산·계산·전기·전기`로 낸다(저장 후 훅이 라인 저장을 모두 마친 뒤 전기한다). 포털은 계산과 전기를 `calcId`로 잇고 순서에 기대지 않으므로 의미 차이는 없다고 본다(질문 3).

## 7. D-6·D-12

- **D-6 영속 판단**: 지급명세(세액·순액)와 지급 시각은 반환값과 배치 실행 컨텍스트에만 있었다 — B-15 "감사 대상 사실은 업무 테이블에"에 맞지 않는다. `PAYOUT_STATEMENT`(V107, `UNIQUE(settlement_id)`, `paid_at` = 정산행 기록·PAID 전이 시각)를 만들고 `settleOne`이 같은 트랜잭션에서 저장, 발행은 그 뒤. `PayoutCompleted.gross`는 `gross_net`이 **아니다**(지시문 D-6 오류) — `gross` = Σ CLAWBACK 아닌 라인, `gross + clawbackOffset = gross_net`을 빌더가 단언(I5에서 실제로 멈췄다).
- **D-12 확정**: 엔진 정정은 `ADJUSTMENT` 행만 만든다(`COMM_CALC`에 ADJUSTMENT 유형 없음) — `AdjustmentPosted`만 발행, 이중 계상 없음. `StatementConfirmed.adjustment` = 그 월 귀속 정정 합(새 읽기 `AdjustmentStore.findByCloseYm`), `PayoutCompleted.adjustment`는 넣지 않는다(정정은 지급되지 않는다 — §11 E1-2).

## 8. CI (엔진, `gh run list --commit`·`gh run view`로 직접 조회)

| 커밋 | push run | pull_request run |
|---|---|---|
| `3fba123` | 38955295938 success | — |
| `46e1de6` | 38958946428 success | — |
| `83dd966` | 38960076717 success | 38960083662 success |
| `0342e3a` | 38962419030 success | 38962424217 success |
| `eca91c4` | 38965269552 success | 38965273693 success |
| `b3aefe9` | 38965566476 success | 38965570662 success |
| `3e8ddcc` | 38966568362 success | 38966575433 success |
| `8393feb` | 38966863992 success | 38966871604 success |
| `9b70f53` | 38967248620 success | 38967255830 success |
| `0b43ad4` | 38967551598 success | **38967562103 success**(§3·§4의 산출물) |

이 보고서 커밋의 CI는 PR에서 확인한 뒤 PR을 리뷰 준비로 바꾼다.

## 9. 계획·승인과 다르게 한 점

1. **업무 모듈 쪽 변경이 계획의 "셋"보다 많다**(전부 읽기·포트 추가, 동작 변경 없음): `AdjustmentStore.findByCloseYm`(정정 합 — 계획 P5가 요구한 값의 출처, 계약 시험 + Oracle 조회 추가), `CommissionCalculator.listeners()`·`RevisionService.hooks()`(조립 일치 시험용 읽기 전용 접근자). Step 클래스는 하나도 고치지 않았다.
2. **분급 전이 지점 네 곳이 사유를 넘긴다**(`DeferralReleaseService` 3, `DeferralReversalHook` 1). 승인 §2의 "기본 메서드로 기존 호출자 무변경"을 "컴파일 호환"으로 읽었다 — 데코레이터는 호출자를 모르므로 사유를 받아야 한다(취소 둘은 상태 변화만으로 구별되지 않는다). 데코레이터는 사유 없는 `replace(entry)`를 거부한다.
3. **골든 실행기를 settlement 시험 픽스처로 옮겼다**(`GoldenCase`·`GoldenCsv`·`GoldenComparator`·`GoldenEngine` + `Wiring` 훅) — 아웃박스 시험이 같은 24건을 데코레이터로 돌리기 위해. `GoldenEngine`에 `DeferralReversalHook`(분급 피처일 때) 추가 — 24건 결과 같음.
4. **정정만 있고 계산이 없는 설계사도 `StatementConfirmed` 한 건**을 받는다(정정은 그 달 명세의 사실). 계획 표의 "설계사마다"를 이렇게 해석했다.
5. **`LimitLedgerOpened`는 직전 `find`가 비었을 때만**(계획 그대로) — E1 이전에 생긴 원장은 개설 이벤트가 없다(부트스트랩 없음, 질문 2).
6. **골든 스냅샷에 `eventId`·`occurredAt`·`seq`도 넣었다**(지시문은 제외하라고 했다) — 고정 시계·순차 eventId라 결정적이고, 순번까지 고정하는 쪽이 엄격하다.
7. **피드 400 `INVALID_REQUEST`**: 파라미터 형식·범위 오류(누락·음수·문자·`limit` 0/1001·앞 0·자릿수 초과). 포털 OpenAPI에는 401·422만 있다(질문 4).
8. **`AgentSnapshot` 값 정의**: `status` 어휘 `ACTIVE`/`DISMISSED`(기준일 해촉 여부), `validFrom` = 위촉일·기준일 유효 등급/소속 이력 시작일·(해촉이면) 해촉일 중 가장 늦은 날. 소속이 없는 설계사는 `orgPath`를 만들 수 없어 실패 → 잡이 격리·보고.
9. **부여 상한 기본 500**(`app.outbox.assign-batch-size`). 지시문 D-1의 "기본 1초 주기"는 엔진에 스케줄러가 없어 두지 않았다(D-1·D-8 지시문 오류, 승인 P8).
10. **C7 시험 이름**: 지시문의 `EventFeedControllerIT(H2)` 대신 계획대로 `EventFeedControllerTest`(MockMvc standalone, 인메모리) + `BootSmokeIT`(Oracle·실 보안 체인).
11. **의존성 검증 메타데이터 POM 4건 추가**: slf4j-api/bom/parent 2.0.17(충돌 해석용 메타데이터 — jar는 BOM의 2.0.18), jackson-dataformat-yaml 2.21.4 pom. 각 SHA-1을 Maven Central 게시값과 대조해 일치. 버전 변경 없음.
12. **설계서 절 번호**: 계획·승인이 "§14"라고 부른 미결정 항목을 엔진 설계서의 미결정 절인 **§11**(E1-1~E1-5)에 두고 본문에 그 사실을 적었다(엔진 설계서에는 §14가 없다).
13. **앱 전역 시계**: `DisclosureGradeConfig`의 `Clock` 빈을 `ClockConfig`로 옮겨 하나로(Asia/Seoul 그대로). `MonthCloseService`는 리스너가 없으면 확정 뒤 레코드를 다시 읽지 않는다(시험 조립의 동작 불변).

## 10. 찾은 결함·사실

| # | 내용 | 조치 |
|---|---|---|
| F1 | **분급 취소가 운영 조립에 없었다**(E1 이전부터) — 운영에서 스케줄이 CANCELLED가 되지 않았다 | `b3aefe9`에서 수정 + 조립 일치 시험(I13) |
| F2 | 한도 이연(DEFER_AFTER_FY)이 삭감과 같은 동작 | 기록(설계서 §11 E1-1, §6.1-4 주석) |
| F3 | 정정(ADJUSTMENT)이 지급되지 않고 호출자가 없다 | 기록(§11 E1-2) |
| F4 | 지급명세·지급 시각 미영속 | `PAYOUT_STATEMENT`(V107) |
| F5 | `closed_at`이 DB 시각이고 CLOSING·OPEN에서도 덮어씀 | 주입된 시계, CLOSED에서만(동작 변경 — 설계서 §7·§5.9) |
| F6 | `LIMIT_LEDGER_HIST` 미사용 | 기록(§11 E1-3), 표 유지 |
| F7 | `DisclosureSourceRulesTest`가 읽는 네 모듈 `src/main`이 Gradle 입력이 아니었다 | `83dd966`에서 선언 |
| P11 | `ClosedMonthGuardedCalcStore`는 운영 조립에 없다. 설계서 §6.4 "과거 마감 숫자는 절대 바뀌지 않는다"를 운영에서 지키는 것은 `CloseYmResolver`와 정정의 `attributionFor`이고 지금의 insert 경로 셋은 전부 그것을 거친다 — **현재로서는 의도된 중복 제거로 보이나, 해석기를 우회하는 새 insert 경로에는 방어가 없다**(결함이라기보다 빠진 이중 방어). 이벤트 쪽 불변식 시험(C8)이 운영 경로로 그 성질을 고정한다 | 기록(§6.4, §11 E1-4) |
| F8 | 채권 `findOffsettable`에 `FOR UPDATE` 없음(지금은 수급자 단위 tx라 경합 없음) | 기록(§11 E1-5) |

## 11. 지시문 오류 여덟 건 (승인 §4, `설계고찰.md` §16)

D-4 `LIMIT_DEFER` 스케줄(엔진에 없다) · D-6 `gross`(= `gross_net`) · D-9 `LIMIT_LEDGER_HIST` 기록과 `postingSeq ≥ 1` · D-10 취소 리스너가 운영에 있다는 전제 · D-14 `closed_at`·`paid_at`에 주입된 시계가 있다는 전제 · D-15 ArchUnit(엔진에 없다 — Gradle 순환 + 원문 스캔) · D-1·D-8 스케줄러·명령(엔진에 없다 — 배치 기동 엔드포인트) · §2-4 "리스너 구현"(insert 경로 셋 중 둘이 리스너 밖).

## 12. 포털에 넘길 질문

1. **조직장 뷰(v2) 이벤트**(D-3): 엔진은 ORG 수급자(오버라이드)의 계산·정산을 v1에 내지 않는다(빌더도 거부). v2는 ① 새 이벤트 타입(예: `OrgCommissionCalculated` — `orgId`·`orgLevel`·원 계산 `calcId`) 또는 ② `CommissionCalculated`에 `recipientKind`/`orgId` 선택 필드 — 포털 §14에 결정 항목으로 기록 부탁. 엔진 쪽 비용은 ①이 작다(빌더·데코레이터 분기 하나, 기존 소비자 무영향).
2. **`TenantSnapshot`**(포털 §14.1 #4, D-2): 엔진은 `OUTBOX_EVENT`를 지우지 않는다(append-only 트리거, 재구축은 `afterSeq=0`). 보존 축소가 정해지면 엔진 쪽은 "지운 구간을 대신할 스냅샷 이벤트"와 전용 롤 삭제 경로가 필요하다. 함께: E1 이전에 생긴 원장·설계사는 개설·스냅샷 이벤트가 없다 — 부트스트랩으로 `AgentSnapshot`은 잡이 있으나 한도 원장 부트스트랩(현재 상태를 `LimitLedgerOpened`+누적으로)은 없다. 필요 여부.
3. **샘플 값 수정 후보**(포털 판단 — 엔진은 샘플에 맞추지 않았다): 02·03·04·05 금액(엔진 시드로 재현 불가 — FY 1,890,000), 06·13(LIMIT_DEFER — 포털은 계약 예시로 유지하기로 함), 08 `StatementConfirmed`의 `withholdingTax`·`net`(마감 시점에 참이 아님 — K4), 13 사유 `POLICY_CANCELLED`(엔진 어휘 `POLICY_TERMINATED`·`SOURCE_REVERSED`·`DUE_RELEASED`·`POLICY_NOT_INFORCE`), 14 채권 `amount`·`remaining` 음수와 `PARTIALLY_OFFSET`(엔진은 양수 금액, 상태 `OPEN`/`OFFSET`/`CLOSED`/`WRITTEN_OFF`), 16 `role`·`nameMasked`(엔진에 없다). 그리고 한 계산의 여러 라인은 `계산…·전기…` 순서로 온다(§6) — 포털이 순서에 기대지 않는지 확인.
4. **피드 400**: 엔진은 파라미터 오류에 400 `INVALID_REQUEST`(Problem 형식)를 낸다. OpenAPI에 400을 추가할지.

## 13. 엔진 쪽 질문

1. 정정만 있는 설계사의 `StatementConfirmed`(§9-4) — 이 해석으로 좋은가.
2. 분급 전이 지점 네 곳의 사유 인자(§9-2) — 승인 §2 문구와의 차이를 인정하는가.
3. P11 — `ClosedMonthGuardedCalcStore`를 운영 조립에 넣을지(이중 방어, 동작 변경은 해석기를 우회하는 경로가 생길 때만).
