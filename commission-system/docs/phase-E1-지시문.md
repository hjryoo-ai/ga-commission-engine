# E1 지시문 — 엔진 아웃박스·이벤트 피드 (ga-commission-engine, v1.0, 2026-10-16)

## 역할과 맥락

당신은 `ga-commission-engine`의 구현자다. 이 Phase는 엔진이 확정한 사실(계산·원장·분급·환수·마감·지급)을 **설계사 포털 `ga-agent-portal`이 읽어 가는 이벤트 스트림**으로 내보낸다. 포털은 돈을 계산하지 않고 이 스트림을 투영해 보여 주므로(포털 설계서 §0), 이 스트림의 **정확성·순서·빠짐없음**이 포털 전체의 전제다.

정본 문서: 엔진 `docs/설계서.md`(여기에 **§6.8 아웃박스·이벤트 피드** 절을 신설한다), 포털 설계서 §4(통합 계약 — 포털 저장소 `docs/설계서.md`), 엔진 `CLAUDE.md`·`CONTRIBUTING.md`·부록 B(매 Phase 전문 포함 규약 — 이 지시문은 그 문서들을 전제로 쓴다). 브랜치 `work/e1`, 태그 `e1`, PR. **병합은 수용 심사 회신 뒤.** 계획 → 승인 → 단계 커밋 → 보고서 → 태그 → PR의 순서는 E3·E3.2와 같다.

**계약은 포털 것이다.** 이벤트 스키마(`contracts/events/v1/**`)와 피드 OpenAPI(`contracts/api/v1/engine-internal.openapi.yaml`)의 정본은 `hjryoo-ai/ga-agent-portal`이고, 엔진은 사본을 들여와 **발행하는 모든 이벤트가 그 스키마를 통과함을 증명**한다(provider). 엔진에서 계약을 고치지 않는다 — 바꿀 일이 있으면 멈추고 보고한다(포털 PR이 먼저다). 이 지시문이 전제하는 포털 계약 판은 **포털 Phase 1 지시문 §0 "계약 v1.1 정비" PR의 병합 커밋**이다(아래 §1 전제).

## 0. 전제 — 포털 계약 v1.1 (포털 쪽 선행 PR, 병합 커밋 해시를 받아 `UPSTREAM`에)

포털 Phase 1 지시문 §0이 다음을 고친다. **이 변경이 병합되기 전에는 2단계(사본)부터 진행하지 않는다**(1단계 골격·계획은 먼저 해도 된다). 고치는 이유는 전부 **엔진에 그 데이터가 없기 때문**이며, 이번 엔진 읽기(2026-10-16, `main` = `85b4c71`)에서 확인한 사실이다:

| 계약 항목 | v1.0(`phase-0` = `28654b6`) | v1.1 | 엔진 사실 |
|---|---|---|---|
| `CommissionCalculated.policySummary.productName`·`insuredMasked` | 필수 | **선택** | 엔진에 계약 마스터·고객 데이터가 없다(`POLICY_EVENT`에 `product_key`·`policy_no`뿐). 포털은 없으면 `productKey`를 보여 준다 |
| `CommissionCalculated.basis.rate`·`payoutRate`·`installmentNo` | 필수 | **선택**(`ruleVersionIds`만 필수) | `applied_rate`·`installment_no`는 NULL 가능(정액·일시 지급), 지급률은 typed 필드가 아니라 `calc_trace`에 있다 |
| `AgentSnapshot.grade`·`role` | 필수 | **선택** | `AGENT_GRADE_HIST`가 없는 설계사가 있을 수 있고, 엔진에 역할(팀장 등)이 없다 — 오버라이드 수급자는 사람이 아니라 `ORG`다. 포털의 역할은 어차피 `identity_link`에서(Phase 3) |
| `StatementConfirmed.clawbackOffset`·`withholdingTax`·`net` | 필수 | **선택** | 원천세·상계는 마감(④ CONFIRMED)이 아니라 **지급 런**(§7 D+1)에서 계산된다(`PayoutService.settleOne`). 마감 시점엔 `gross`·`adjustment`만 참이다 |
| `PayoutCompleted` | `agentId, closeYm, paidAt, paidAmount` | + 선택 `runSeq, gross, clawbackOffset, receivableOffset, carriedReceivable, adjustment, payable, withholdingTax, net` | 지급 런이 세액·상계를 확정하는 자리다. `paidAmount` = `net`(세후 실지급) |
| 피드 응답 `FeedResponse` | `events, nextSeq, headSeq, schemaVersion` | + 필수 **`tenantId`** | 엔진은 테넌트별 인스턴스(`app.tenant-id`). 포털이 잘못된 `engine_base_url`로 다른 테넌트 인스턴스를 읽는 사고를 응답에서 막는다 |
| 피드 오류 | — | `422 AFTER_BEYOND_HEAD`(`afterSeq` > `headSeq` — 복원된 DB 등 다른 발행자) | ga-disclosure 피드와 같은 규약 |
| 금액 뜻(설명문) | 없음 | `amount` = 한도 삭감 뒤·분급 분할 뒤 **지급 예정액**, 요청액 = `amount + limitCutAmt`, 분급 이연분은 `DeferralScheduled`로 | `LimitGateStep.withLimitCut(payable, cut)`, `DeferralSplitStep.withAmount(immediate)`에서 확인 — 포털 I1(DD-2)이 맞았다 |

## 1. 결정 (계획에서 뒤집으려면 이유를 먼저)

| # | 결정 | 근거·조건 |
|---|---|---|
| D-1 | **`seq`는 Oracle 시퀀스가 아니라 순번 부여기(`OutboxSequencer`)가 커밋된 행에만 붙인다.** `OUTBOX_EVENT(event_no IDENTITY PK, seq NUMBER NULL UNIQUE, …)`. 업무 트랜잭션은 `seq` NULL로 insert만 한다. 부여기는 단일 실행(한 행짜리 `OUTBOX_SEQ` 표의 `SELECT … FOR UPDATE`로 직렬화) — 보이는(=커밋된) 미부여 행을 `event_no` 순으로 `max(seq)+1`부터 **빈틈 없이** 붙인다. 피드는 `seq`가 붙은 행만 돌려준다 | 시퀀스는 롤백·캐시로 갭이 나고, 낮은 번호가 늦게 커밋되면 poller가 건너뛴다 — 포털은 갭에서 **정지**한다(포털 §6.1). 같은 원장·같은 설계사의 인과 순서는 엔진의 락(원장 `FOR UPDATE`, 수급자 단위 지급 트랜잭션)이 커밋 순서를 보장하므로 `event_no` 순 부여로 보존된다 — 이것을 **설계서 §6.8에 증명 문장으로** 적는다. 부여 주기는 파라미터(기본 1초, 배치 잡 또는 스케줄러), 피드 호출 시 lazy 부여도 허용(둘 다 같은 락) |
| D-2 | **append-only.** `OUTBOX_EVENT` UPDATE(seq 부여 제외)·DELETE는 Oracle 트리거가 거부(`DISC_GRADE_SNAPSHOT` V103과 같은 방식), H2 티어는 저장소 포트가 메서드를 제공하지 않는 것으로 | 보존 축소는 포털 §14.1 #4(`TenantSnapshot`) 미결정 — 지금 만들지 않는다 |
| D-3 | **발행 대상은 `recipient_type = AGENT` 행만.** `ORG` 수급자(오버라이드)의 `COMM_CALC`·정산행은 v1에 발행하지 않는다 | 포털 v1은 설계사 본인 화면이고 계약의 `agentId`가 필수다. 조직장 뷰(v2)는 새 이벤트 또는 `recipientKind` 추가로 — 포털 §14에 기록하도록 보고서 질문에 |
| D-4 | **금액·분급**: `CommissionCalculated.amount` = `COMM_CALC.calc_amount`(삭감 뒤·분할 뒤), `limitCutAmt` = `limit_cut_amt`, `limitCutPolicy` = 룰의 `over_limit_action`(삭감이 0이면 생략). FY_COMM 분할의 이연분은 `DeferralScheduled`(reason `CURVE`), 한도 이연분(DEFER_AFTER_FY)도 `DeferralScheduled`(reason `LIMIT_DEFER`) — 둘 다 `DeferralSchedulePoster`가 만드는 스케줄에서 | 포털 합산(Σ 라인)이 이중 계상되지 않는다 — 이연분은 도래 시 `DEFERRED` 라인으로 다시 온다 |
| D-5 | **`StatementConfirmed`** = 마감 ④에서 설계사마다 1건: `gross` = Σ 그 달 `AGENT` 레코드 중 `comm_type ≠ CLAWBACK`의 `calc_amount`(**전 상태 합산** — B-4, `REVERSED` 원본과 reversal 모두 포함), `clawbackOffset` = Σ `CLAWBACK` 라인(음수), `adjustment` = Σ `ADJUSTMENT`(그 `close_ym`로 귀속된 것), `closedAt`. 세액·순액은 **넣지 않는다**(v1.1에서 선택) | 포털 `earnings_month`의 세 합산과 정의가 같아야 대사(포털 §6.6)가 성립한다. 합산은 `NetAmountCalculator`를 쓰되 유형별 분리는 이벤트 빌더가 한다 — 상태 필터 금지 |
| D-6 | **`PayoutCompleted`** = `PayoutService.settleOne` 트랜잭션 안에서 설계사마다 1건: `runSeq`, `gross`(= `gross_net`), `receivableOffset`(= `receivable_offset`), `carriedReceivable`, `payable`, `withholdingTax`(= `incomeTax + localTax`), `net`(= `netPay`), `paidAmount` = `net`, `paidAt`. `clawbackOffset`·`adjustment`는 D-5와 같은 정의로 다시 | 지급명세 사실(세액)이 지금은 반환값으로만 존재하고 영속되지 않는다 — 아웃박스 행이 **그 사실의 첫 정본**이 된다(B-15 "감사 대상 사실은 업무 테이블에"와 맞는지 계획에서 판단: 맞지 않으면 `PAYOUT_STATEMENT` 표를 같이 만들고 그 저장 뒤 발행) |
| D-7 | **`PolicyEventObserved`**는 `POLICY_EVENT` 저장 시점에 `CANCEL`·`LAPSE`·`WITHDRAW`·`REDUCE`·`REVIVE`만 발행, `REVIVE` → 계약 열거 `REINSTATE`로 매핑. `NEW`·`PAYMENT`·`CORRECT`는 발행하지 않는다. `PAYMENT_OVERDUE`는 엔진에 없으므로 발행되지 않는다(계약 열거에는 남아 있다 — 다른 공급원용) | 포털 §4.2 표. 매핑 표는 코드 상수가 아니라 **빌더 한 곳**에 두고 테스트로 고정 |
| D-8 | **`AgentSnapshot`**: 마스터 변경 경로(저장소 포트)가 있으면 그 경로에, 없으면 **부트스트랩 명령**(`outbox snapshot-agents`, 전 설계사 1건씩)만. `orgPath`는 `AGENT_ORG_HIST` 기준일 유효 행과 `ORG_MST` 부모 체인으로 `/{HQ}/{BRANCH}/{TEAM}`(org_id), `status`는 `dismissed_on`으로 `ACTIVE`/`DISMISSED`, `validFrom` = 관련 이력 중 가장 늦은 `apply_from` | 포털 재구축(§6.7)의 부트스트랩이기도 하다 — 명령은 멱등(같은 내용을 다시 발행해도 포털 upsert) |
| D-9 | **`LimitLedgerOpened`**는 원장이 처음 생길 때(`LimitLedgerStore.getOrCreate`가 생성한 경우) 같은 트랜잭션에서, **`LimitLedgerPosted`**는 `LimitLedgerPoster`·`LimitReversalHook`·환수 차감의 모든 DTL 전기마다(`postingSeq` = DTL `posting_seq`, `accumAfter` = 전기 직후 `accum_paid`), **`LimitLedgerRebased`**는 `PremiumRepriceStep`의 `LIMIT_LEDGER_HIST` 기록마다 | `accumAfter`는 포털이 그대로 저장한다(합산 금지) — 전기 직후 값을 **같은 락 안에서** 읽어 넣는다 |
| D-10 | **`DeferralScheduled`**는 `DeferralSchedulePoster`, **`DeferralStatusChanged`**는 `DeferralReleaseService`(→ `RELEASED`)·`DeferralCancelListener`/`DeferralReversalHook`(→ `CANCELLED`)·`HELD` 전이 지점 전부. `reason`은 전이 지점이 정한 닫힌 어휘(계획에 표) | — |
| D-11 | **`ClawbackReceivableUpdated`**는 `ClawbackOffsetService.capitalize`(생성)·`consume`(상계) 뒤 채권 행 상태마다 | — |
| D-12 | **`AdjustmentPosted`**는 `AdjustmentService`가 `ADJUSTMENT` 행을 만들 때. 엔진이 그 정정에 대해 `COMM_CALC` 레코드도 만든다면 **둘 다 발행하지 않고** 계획에서 하나로 정한다(포털은 `AdjustmentPosted`를 `commType=ADJUSTMENT` 라인으로 저장한다 — 이중 계상 금지) | 계획에서 `AdjustmentService`를 읽고 확정 |
| D-13 | **피드 `GET /internal/v1/events`를 E1에 포함**한다(포털 설계서 §12는 E2였다 — 포털 Phase 1이 실 공급자 없이 끝나지 않게 앞당긴다; 포털 §12 반영은 포털 Phase 1이 한다). 기존 `/internal/**` 서비스 토큰 체인(E3)을 그대로 쓴다. 응답 `tenantId` = `app.tenant-id`. `afterSeq` 필수(0부터), `limit` 1~1000 기본 200, `seq` 오름차순·갭 없음·`seq`가 붙은 행만, `nextSeq` = 돌려준 마지막 `seq`(없으면 `afterSeq`), `headSeq` = 부여된 최대 `seq`, `afterSeq > headSeq` → 422 `AFTER_BEYOND_HEAD` | 시뮬레이션 2종·명세 조회·`StatementEstimated`는 **E2** |
| D-14 | **Envelope**: `eventId` UUID(발행 시 생성, 저장), `type`·`version`(=1)·`occurredAt`(업무 시각 — 저장 시각이 아니라 사실의 시각: `created_at`·`closed_at`·`paid_at`, 주입된 시계)·`aggregate{kind,id}`(포털 계약의 9종 짝 — `STATEMENT`·`PAYOUT`은 `{agentId}:{closeYm}`)·`payload`. 테넌트는 Envelope에 없다 | 포털 Envelope `if/then`이 `type ↔ kind` 짝을 강제한다 |
| D-15 | **모듈**: 새 모듈 `commission-outbox`(Spring 무의존 — 이벤트 빌더·직렬화 포트·`OutboxStore` 포트·`OutboxSequencer`·인메모리 레퍼런스)가 **업무 모듈을 의존**한다(`outbox → calc/limit/deferral/clawback/settlement → rule → domain`). 업무 모듈에 통지 지점이 없으면 **최소 리스너 포트**(인터페이스)만 더하고 Step 로직은 건드리지 않는다(B-12). Oracle 어댑터는 `commission-infra`, 컨트롤러는 `commission-api`, 부여기·부트스트랩 잡은 `commission-batch` | 반대 방향(업무 모듈 → outbox)은 금지 — ArchUnit에 추가 |
| D-16 | **계약 사본 구조**: `contracts/shared/<업스트림>/` + 각자의 `CHECKSUMS`·`UPSTREAM`. 기존 ga-disclosure 사본(`contracts/api/v1/engine-disclosure.openapi.yaml`)을 `contracts/shared/ga-disclosure/api/v1/`로 옮기고 거기에 `events/v1/envelope-core.schema.json`도 들인다(포털 Envelope가 그것을 `allOf[0]`으로 참조). 포털 사본은 `contracts/shared/ga-agent-portal/{events/v1/**, api/v1/engine-internal.openapi.yaml}`. `verifyContractChecksums`·`verifyUpstreamContract`는 디렉터리마다. 스키마 IRI 해석은 **저장소 파일로만**(`https://ga.example/contracts/` → ga-disclosure 사본, `https://ga.example/portal/contracts/` → 포털 사본, 원격 조회 0) — 포털 `EventSchemaTest`와 같은 방식 | 포털 Phase 0 수용 심사 Q3. 옮기는 커밋은 바이트 동일(`CHECKSUMS` 경로만 바뀜) |
| D-17 | **마이그레이션**: 공통 `V105__outbox_event.sql`(H2 겸용), Oracle 전용 `V106__outbox_event_oracle.sql`(`IS JSON`, 트리거). 번호는 전역 단조 규칙(`db-migrations.md`) | — |

## 2. 작업 목록 (단계 = 커밋 단위, 단계마다 테스트·주입·설계서 같은 커밋)

1. **계획** `docs/phase-E1-계획.md`: 발행 지점 표(이벤트 → 모듈·클래스·트랜잭션 경계 → payload 필드 ↔ 엔진 컬럼/값), D-6 영속 판단, D-12 확정, `DeferralStatusChanged.reason` 어휘, `AgentSnapshot` 경로, 부여기 실행 방식, ORG 제외 범위, 모듈 의존 그림, 완료 기준 ↔ 테스트, 주입 목록(ID). **승인 뒤 2단계.**
2. **계약 사본 재구성 + 들이기**(D-16): 디렉터리 이동(바이트 동일), 포털 v1.1 병합 커밋 사본, 두 `UPSTREAM`·`CHECKSUMS`, 검증 태스크 확장, IRI 해석기, 포털 샘플 17종이 엔진 쪽 검증기로도 전부 통과(소비자 검증기와 공급자 검증기의 판정 일치).
3. **아웃박스 모듈·스키마·저장소**(D-1·D-2·D-15·D-17): `OUTBOX_EVENT`·`OUTBOX_SEQ`, `OutboxStore` 포트 + 인메모리 + Oracle(MyBatis) 어댑터 — **계약 테스트 스위트**(testFixtures `contract/`) 상속, `OutboxSequencer`(갭 없음·단조·동시 실행 직렬화 — Oracle IT로 실제 경합 2건), append-only 트리거.
4. **발행 지점 연결**(D-3~D-12): `CalcResultListener` 구현(`CommissionCalculated` 라인마다), 한도 3종, 분급 2종, 채권, 정책 이벤트, 마감(`StatementConfirmed`), 지급(`PayoutCompleted`), 정정(`AdjustmentPosted`). 각 발행은 **업무 트랜잭션 안**(인메모리 레퍼런스에서도 같은 단위로).
5. **피드 API**(D-13) + `AgentSnapshot` 부트스트랩 명령(D-8) + 부여기 잡.
6. **공급자 증명**: ⓐ **테스트 전역 검증기** — 전체 테스트 스위트에서 발행된 모든 이벤트를 포털 스키마로 검증해 위반 0(테스트 하네스의 아웃박스 저장소 데코레이터 — 어떤 테스트가 어떤 이벤트를 냈든 전부). ⓑ **골든 시나리오 스냅샷** — 설계서 부록 A 골든 케이스(들)를 파이프라인으로 실행해 발행 시퀀스를 `src/test/resources/outbox-golden/<case>.jsonl`로 고정(`eventId`·`occurredAt`·`seq` 제외한 결정적 필드), 변경은 의도된 것만. ⓒ **포털 A-1 대조** — 포털 샘플 01~13(`contracts/shared/ga-agent-portal/events/v1/samples/`)과 같은 사건(2026-08-01 체결·월납 300,000·FY_COMM 2,800,000·INCENTIVE 800,000 삭감 200,000 DEFER_AFTER_FY·해약)을 엔진 골든 데이터로 재현해 **타입 순서와 금액 필드**가 같음을 대조. 엔진 룰 시드로 같은 금액이 안 나오면 억지로 맞추지 말고 **차이 표**로 보고(포털 샘플이 예시값이면 포털 쪽 수정 후보).
7. **마감 불변식(이벤트 측)**: `StatementConfirmed(agent, ym)` 뒤 그 `(agent, ym)`의 `CommissionCalculated`가 발행되지 않음(`ClosedMonthGuardedCalcStore`가 막는 것을 이벤트 스트림에서 다시 단언), `PayoutCompleted` 뒤 같은 `(agent, ym)`의 두 번째 `PayoutCompleted` 없음(런 반복 무해).
8. **문서**: 설계서 **§6.8 신설**(테이블·부여기 증명·발행 지점 표·피드 규약·ORG 제외·보존), §4.1 모듈 표에 `commission-outbox`, §10 로드맵(E1 완료·E2 범위 — `StatementEstimated`·시뮬레이션·명세), `db-migrations.md`, `CLAUDE.md`의 `contracts/` 문장(업스트림 둘), `설계고찰.md`(D-1 — 왜 시퀀스가 아닌가), 보고서, 태그, PR.

## 3. 완료 기준 (전부 테스트·CI 아티팩트로)

| # | 기준 | 증명 | 주입(ID) |
|---|---|---|---|
| C1 | 발행된 **모든** 이벤트가 포털 Envelope(allOf core)·payload 스키마를 통과 — 전체 스위트 위반 0 | 전역 검증기 집계(건수 보고) | I1 필수 필드 하나 빼고 발행 → 전역 검증기 실패 |
| C2 | `seq` 갭 없음·단조·커밋된 행만, 동시 부여 직렬화 | `OutboxSequencer` 계약 스위트 + Oracle 경합 IT(미커밋 트랜잭션의 행이 **부여되지 않고**, 커밋 뒤 다음 번호를 받음) | I2 부여기 락 제거 → 경합 IT 실패(중복/갭) |
| C3 | 업무 트랜잭션과 같은 트랜잭션 — 롤백이면 이벤트 없음 | 각 발행 지점 1건씩: 저장 뒤 강제 롤백 → `OUTBOX_EVENT` 0행 | I3 발행을 별도 트랜잭션으로 → 실패 |
| C4 | 금액 뜻(D-4): 삭감·분할 케이스에서 `amount + limitCutAmt` = 요청액, Σ `DeferralScheduled.amount` = 이연 원금 | 골든 스냅샷 + 단위 | I4 `amount`를 요청액으로 → C4·골든 실패 |
| C5 | D-5·D-6 정의대로 `gross`·`clawbackOffset`·`adjustment`·세액·순액 — 정정(REVERSED+reversal) 공존 케이스에서 전 상태 합산 | 마감·지급 단위 + 골든 | I5 `gross`에 상태 필터 → 실패 |
| C6 | ORG 수급자 행은 어떤 이벤트에도 없다 | 전역 검증기의 `agentId` 집합 ∩ ORG id = ∅ | I6 ORG 라인 발행 → 실패 |
| C7 | 피드: 순서·갭 없음·`tenantId`·`nextSeq`/`headSeq`·`limit` 경계·`afterSeq > headSeq` 422·서비스 토큰 없이 401·부여되지 않은 행 미노출 | `EventFeedControllerIT`(H2) + OpenAPI 대조(응답이 `FeedResponse` 스키마 통과) | I7 미부여 행 노출 → 실패, I8 `tenantId` 누락 → 계약 실패 |
| C8 | 마감 불변식(이벤트 측), 지급 반복 무해 | 7단계 테스트 | I9 마감 가드 우회 → 실패 |
| C9 | 계약 사본: 두 업스트림 바이트 동일·`UPSTREAM` = 병합 커밋·`format` 0·IRI 원격 조회 0 | `verifyContractChecksums`(check)·`verifyUpstreamContract`(CI) | I10 포털 사본 1바이트 + CHECKSUMS 재생성 → 업스트림 대조 실패(포털 K7과 같은 틈) |
| C10 | 골든 시퀀스 스냅샷 일치, 포털 A-1 대조(또는 차이 표) | 6단계 ⓑ·ⓒ | I11 골든 케이스 금액 하나 변경 → 스냅샷 실패 |
| C11 | append-only(Oracle 트리거), 모듈 의존 방향(`outbox → 업무`, 역방향 0), jqwik 0, E3.2 무손상(전체 체크, 빠른 티어 + 풀 빌드) | IT·ArchUnit·CI 2단 | I12 업무 모듈에서 outbox 참조 → ArchUnit 실패 |

주입은 **ID 하나 단위**로 세고 커밋 본문에 주입 → 실패 → 제거.

## 4. 하지 말 것

`StatementEstimated`·시뮬레이션 2종·명세 조회 API(E2). 포털 계약 수정(멈추고 보고). `COMM_CALC` 스키마·Step 로직 변경. ORG 수급자 발행. 아웃박스 UPDATE/DELETE 경로. HTTP ack(포털 피드는 `afterSeq`만). 브로커·메시징 의존성. 룰 데이터 하드코딩(한도 정책명 등은 룰에서 읽는다).

## 5. 보고 형식

E3.2와 같게. 추가로: 발행 지점 표(이벤트 → 클래스 → 트랜잭션), 전역 검증기 집계(이벤트 수·타입별), 골든 스냅샷 파일 목록, 포털 A-1 대조 결과(또는 차이 표), `UPSTREAM` 두 줄, D-6 영속 판단과 D-12 확정, 포털에 넘길 질문(조직장 v2 이벤트, `TenantSnapshot`, 포털 샘플 값 수정 후보).
