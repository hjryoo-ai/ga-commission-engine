# E1 계획 승인 (2026-10-17) — ga-commission-engine

## 판정: 승인. Q1~Q5 전부 권장안. P4~P12 권장안 전부 수용. `UPSTREAM`은 포털 계약 **v1.1.1** 병합 커밋(포털 쪽 선행 PR — 해시를 받으면 2단계)

읽은 것: `work/e1` = `3fba123`의 `docs/phase-E1-계획.md` 전문. P3·P5는 `CalcPipelineConfig`·`ServiceConfig`·`AdjustmentService` 참조 코드를 직접 다시 확인했다(조립에 `DeferralCancelListener`·`DeferralReversalHook` 없음, `AdjustmentService`는 빈으로만 있고 호출자 없음 — 맞다). P1은 지시문을 쓸 때 내가 `LimitGateStep`을 읽고도 "메모만 다르다"를 "이연 스케줄이 있다"로 잘못 읽었다.

## 1. 인정하는 것

- **열두 전제를 코드 행 번호로 반증한 것.** 특히 P1(한도 이연이 삭감과 같은 동작), P3(취소 리스너가 운영 조립에 없음), P5(정정이 지급되지 않음), P6(지급명세가 영속되지 않음)은 E1과 무관하게 **엔진에 이미 있던 결함**이고, 아웃박스를 붙이려다 드러났다. 이것이 "사실을 밖으로 내보내는" 작업의 가치다 — 보고서와 `설계고찰.md`에 그렇게 적어라.
- **P12 — 발행을 리스너가 아니라 저장소 데코레이터에.** `COMM_CALC` insert 경로가 셋이고 둘이 리스너를 거치지 않는다는 발견이 결정을 바꿨다. "Step·업무 코드 무변경"을 지키면서 빠짐없음을 구조로 얻는다.
- **P4 — `gross + clawbackOffset = gross_net`을 빌더가 단언**하도록 한 것. 두 정의(포털 합산·엔진 순액)가 어긋나면 발행 시점에 멈춘다.
- §3 증명 문장 ①②③과 "같은 잠금을 공유하지 않는 두 사실의 순서는 보장하지 않는다"는 한계 명시.

## 2. 결정

| # | 결정 | 조건 |
|---|---|---|
| Q1 / P1 | **`LIMIT_DEFER` 발행 없음.** `limitCutPolicy`는 룰의 `over_limit_action` 그대로 | 포털 계약 v1.1.1(L3)이 그 뜻을 `description`에 적는다. 엔진 설계서 **§14에 "DEFER_AFTER_FY 실제 이연(초년도 종료 후 지급) 미구현 — 새 Step"** 추가, §6.1-4의 "② 초년도 종료 후 이연"에 "(현재 삭감과 동일 동작, §14)" 주석. 포털 샘플 06은 계약 예시로 남는다(포털 결정) — 차이 표에. |
| Q2 / P2 | **`LimitLedgerRebased.postingSeq` 선택** — 포털 v1.1.1(L1)이 완화한다. **E1에서 발행한다**(보류 안 함) | 데코레이터가 `save`에서 `limit_amount` 변화를 보고 발행. `postingSeq`는 전기가 있으면 마지막 `posting_seq`, 없으면 생략. `reason` 어휘 `PREMIUM_REDUCED`/`PREMIUM_INCREASED`(둘 다 닫힌 열거 — 증액 재산정도 `PremiumRepriceStep`가 다루면). `LIMIT_LEDGER_HIST`를 아무도 쓰지 않는 사실은 §14 또는 §5.6 주석에(표를 지우지는 않는다 — 마이그레이션 불변). |
| Q3 / P3 | **별도 커밋으로 앱 조립에 추가** | 그 커밋에 **조립 시험**: 운영 조립의 `CalcResultListener`·`ReversalHook` 목록 = 골든 엔진 조립 목록(클래스 집합 비교) — 다시 어긋나면 실패. 주입 **I13** 조립에서 하나 제거 → 실패. 엔진 DECISIONS/설계고찰에 "E1 이전부터의 결함, E1에서 수정". |
| Q4 / P9 | **ArchUnit 들이지 않는다** — Gradle 순환(I12) + 원문 스캔 | 원문 스캔은 `main` 소스의 `import ga.comm.outbox` 0 + 모듈 `build.gradle.kts`의 `project(":commission-outbox")` 참조는 `infra`·`api`·`batch`·`app`만. |
| Q5 / P7 | **앱 전역 `Clock` 빈, `closedAt` 인자** | `SettleCloseStore.transition`이 `CLOSED`일 때만 `closed_at`을 쓰고 CLOSING·OPEN 전이는 건드리지 않는다(지금의 덮어쓰기는 동작 변경 — 설계서 §7·§5.9 주석, 보고서에). `DisclosureGradeConfig`의 Asia/Seoul 시계와 **하나로**(공시 스냅샷 번호의 날짜 규약이 깨지지 않게 — 시간대는 그대로, 빈만 통합). |
| P4 | 승인 | `PayoutCompleted.gross` ≠ `gross_net` — 지시문 D-6 오류, 보고서 "지시문 오류"에. |
| P5 | 승인 — `AdjustmentPosted`만, `PayoutCompleted.adjustment` 없음 | 엔진 §14 "정정(ADJUSTMENT)이 마감·지급에 읽히지 않는다 — 호출자도 없다". `StatementConfirmed.adjustment`는 넣는다(원장의 사실). |
| P6 | **`PAYOUT_STATEMENT` 승인**(V107) | 컬럼: `run_seq`·수급자·`payable`·`income_tax`·`local_tax`·`net_pay`·`paid_at`·`settlement_id`(FK) + `UNIQUE (settlement_id)`. `paid_at` = 정산행 기록·PAID 전이 시각(포털 v1.1.1 L2와 같은 뜻). 설계서 §5.9 추가, §7 ③에 "지급명세 영속". B-15에 맞는다. |
| P8 | **lazy 부여 + 배치 잡** 승인 | 피드 핸들러 안에서 부여는 **쓰기 트랜잭션 하나로 먼저 끝내고**, 읽기는 그 뒤 별도(읽기 전용) 트랜잭션. 부여 트랜잭션은 짧게(한 번에 최대 N건, 파라미터). |
| P10·P11·P12 | 승인 | P11: `ClosedMonthGuardedCalcStore`가 운영 조립에 없다는 사실도 보고(결함인지 의도인지 판단은 설계서 §6.4 문구와 대조해 적는다 — 리졸버가 OPEN 월로 보내므로 가드는 중복일 수 있다). |
| §2 `replace(entry, reason)` 오버로드 | 승인 — 분급 모듈의 유일한 포트 변경 | 기본 메서드로 기존 호출자 무변경. |
| §3 | 승인 | 증명 ③에 **단일 인스턴스 가정**을 적는다(IDENTITY의 단조성은 단일 인스턴스에서 성립, RAC는 범위 밖). H2 티어는 `synchronized` 인메모리와 공통 V105 둘 다. |
| §5 검증기 판정 일치 | 승인 — 2단계에 | 1.5.9 ↔ 3.0.6 차이가 나오면 **엔진 쪽을 올리지 말고**(의존성 올림은 별도) 어느 쪽이 2020-12에 맞는지 적고 보고 — 그 자리에서 결정. |
| C10 골든 | 승인 | 포털 A-1 대조는 **차이 표**(P1 샘플 06, FY 2,800,000 vs 1,890,000)와 함께 "타입 순서 일치 여부"를 따로 — 금액이 달라도 순서가 같으면 그것이 증거다. |

## 3. 선행 — 포털 계약 v1.1.1

포털 Phase 1 계획 승인 §3에서 지시했다: L1 `LimitLedgerRebased.postingSeq` 선택, L2 `paidAt` 설명, L3 `limitCutPolicy` 설명. 포털 세션이 PR을 병합하면 **그 병합 커밋이 `contracts/shared/ga-agent-portal/UPSTREAM`** 이다(`f30d026`이 아니라). 해시를 받기 전에는 2단계(사본)로 가지 않는다. 1단계(승인 보관 + 계획 갱신)는 지금.

## 4. 지시문 오류 (보고서 §"지시문 오류"와 `설계고찰.md`)

- D-4 `LIMIT_DEFER` 스케줄(엔진에 없다 — 코드를 읽고도 오독).
- D-6 `gross`(= `gross_net`) — 계약 정의(CLAWBACK 제외)와 다르다.
- D-9 `LIMIT_LEDGER_HIST` 기록(아무도 쓰지 않는다), `postingSeq ≥ 1` 가정.
- D-10 취소 리스너가 운영에 있다는 전제.
- D-14 `closed_at`·`paid_at`에 주입된 시계가 있다는 전제.
- D-15 "ArchUnit에 추가"(엔진에 ArchUnit 없음).
- D-1 "배치 잡 또는 스케줄러", D-8 "명령"(엔진에 스케줄러·CLI 없음).
- §2-4 "리스너 구현" — insert 경로 셋 중 둘이 리스너 밖.

여덟 건 — 엔진 코드를 한 번 읽고 지시문을 쓴 결과다. 계획이 이것을 전부 잡았으므로 절차는 작동했지만, 다음 엔진 지시문(E2)은 **계획을 먼저 받고 결정만 내리는 형식**으로 쓴다.

## 5. 절차

승인 보관 + 계획 갱신 1커밋(Q2 `reason` 어휘, Q3 조립 시험·I13, Q5 CLOSED만, P8 트랜잭션 분리, §3 가정, §4 목록) → 포털 v1.1.1 해시 수신 → 2단계~8단계. 주입 13건(I1~I13), ID 단위. 보고서·`e1` 태그·PR까지, 병합은 수용 심사 뒤.
