# E2 지시문 — 잠정 명세·시뮬레이션·명세 조회 (ga-commission-engine, v1.0, 2026-10-17)

## 형식에 대해

E1에서 지시문의 전제 아홉 건이 엔진 코드와 달랐다. 그래서 이 지시문은 **범위·완료 기준·질문**만 적고, 발행 지점·트랜잭션·모듈·dry-run 구조는 **계획이 정한다**. 계획은 각 항목에 "엔진 사실(파일:행) → 설계"를 붙이고, 지시문의 어느 문장이 코드와 다르면 P-번호로 반증한다(E1 계획 §0과 같은 표). 승인은 계획의 결정에 대해서만 한다.

브랜치 `work/e2`, 태그 `e2`, PR, **병합은 수용 심사 회신 뒤**. 정본은 엔진 `docs/설계서.md`(§6.8 신설분 포함), 포털 설계서 §4.3(동기 API)·§4.2(`StatementEstimated`), 포털 계약(정본은 포털 — 엔진에서 고치지 않는다). `CLAUDE.md`·부록 B 전제.

## 0. 전제·선행

- 포털 계약 **v1.2**(포털 Phase 2 지시문 §0 — 피드 400, `DeferralStatusChanged.reason`·`ClawbackReceivableUpdated.status` 닫힌 열거, 채권 금액 `minimum 0`) 병합 커밋이 `contracts/shared/ga-agent-portal/UPSTREAM`이다. 어휘가 엔진 빌더의 열거와 어긋나면 **멈추고 보고**(어휘의 정본은 엔진 열거 — 포털이 고친다).
- E1 수용 심사(`docs/phase-E1-수용심사.md`)의 결정: Q3 `ClosedMonthGuardedCalcStore` 조립은 **이번에**, 스냅샷 부트스트랩은 **후보**.

## 1. 범위

| # | 항목 | 요구 |
|---|---|---|
| ⓪ | **A-1 정본 스트림** | `PortalA1ComparisonTest`(또는 그 실행기)가 **입력(월납·체결일·시책·마감·해약)을 받아** 스트림을 쓰게 하고, **월납 150,000**(한도 1,800,000 — 시책 1,000,000이 실제로 초과해 삭감이 일어나는 값)으로 낸 스트림을 `commission-outbox/src/test/resources/outbox-golden/a1_portal_150000.jsonl`로 커밋한다(고정 시계·순차 `eventId`·`seq` 포함). 포털이 이것을 샘플 정본으로 들인다. 이 단계는 **첫 커밋으로 먼저 push**해 포털 Phase 2가 받게 한다. E2 끝에 `StatementEstimated`가 포함된 **같은 입력의 두 번째 판**(`a1_portal_150000_e2.jsonl`)을 더 낸다 — 첫 판의 이벤트는 바이트 그대로 포함돼야 한다(추가만). |
| ① | **`StatementEstimated`** | 지급 런의 **dry-run**: 그 달 열린 레코드의 순액(`NetAmountCalculator`)·채권 상계 예상·원천세 예상 → 설계사마다 1건, `basisSeq` = 산출 시점의 아웃박스 최대 부여 `seq`, `asOf` = 주입된 시계. **아무것도 쓰지 않는다**(채권 `remaining`·`COMM_CALC` 상태·정산행·`PAYOUT_STATEMENT` 전부) — 아웃박스 행 하나만 남는다. 주기는 배치 파라미터(**기본 야간 1회**), 온디맨드(③의 `mode=ESTIMATE`)는 **같은 서비스**를 다른 트리거로. 마감된 달에는 내지 않는다(그 달은 `StatementConfirmed`가 정본). |
| ② | **시뮬레이션 2종** | `POST /internal/v1/simulations/new-contract`·`/policy-event`(포털 계약 `engine-internal.openapi.yaml` §4.3 — 응답 모양은 계약이 정본). 파이프라인·환수 계산을 **영속화 없이** 실행: 원장은 읽기 스냅샷(락 없음), 룰은 요청 기준일, `ruleSnapshotId`는 **같은 입력·같은 룰 버전이면 같은 값**(재현 가능 — 룰 버전 ID 집합의 해시 등, 계획이 정한다). 저장되는 것은 없다(포털이 `simulation_log`에 남긴다). 레이트 리밋은 포털 몫(엔진은 하지 않는다). |
| ③ | **명세 조회** | `GET /internal/v1/agents/{agentId}/statements/{ym}?mode=ESTIMATE\|CONFIRMED` — `ESTIMATE`는 ①의 서비스를 지금 실행한 결과(발행은 하지 않는다 — 조회는 이벤트가 아니다), `CONFIRMED`는 마감·지급 사실(`StatementConfirmed`+`PayoutCompleted`와 같은 값)을 돌려준다. 포털 Phase 7 대사의 입력. |
| ④ | **`ClosedMonthGuardedCalcStore` 조립**(E1 P11) | 운영 조립에 이중 방어로. 해석기를 거치는 기존 경로의 동작 변경 0(골든 24건·E1 골든 5건 동일). ①②의 dry-run이 **이 가드를 우회하지 않음**(dry-run은 insert 자체가 없어야 한다). |
| ⑤ | **로컬 스택 한 명령** | `docker compose`(또는 스크립트 하나): Oracle Free 컨테이너(digest 고정) + 마이그레이션 + **시드**(⓪의 A-1 입력 + 설계사 마스터·조직) + 서비스 토큰(환경변수, 저장소에 값 0) + 엔진 앱 기동 → `GET /internal/v1/events?afterSeq=0`이 A-1 스트림을 돌려준다. 포털 `e2e/engine-feed/`가 이것을 상대로 돈다. README "로컬 실행". |
| ⑥ | **현재 상태 스냅샷 부트스트랩(후보)** | 엔진 운영 중 포털 도입(seq 0이 과거가 아님)을 위한 잡: 설계사(이미 있음)·한도 원장(`LimitLedgerOpened` + 현재 누적을 표현할 수 있는 형태)·분급·채권의 현재 상태. **계약에 없는 형태가 필요하면 계약 v2 항목**으로 보고만 하고 구현하지 않는다. 비용이 작고 계약 안에서 되면 넣는다 — 계획에서 판단. |

## 2. 완료 기준

| # | 기준 | 증명(계획이 시험 이름을 정한다) |
|---|---|---|
| C1 | ①·②·③ 실행 전후 **모든 업무 테이블 행 수·내용 동일**(`COMM_CALC`·`LIMIT_LEDGER(_DTL)`·`DEFERRAL_SCHEDULE`·`CLAWBACK_*`·`AGENT_SETTLEMENT*`·`PAYOUT_STATEMENT`·`POLICY_EVENT`), 아웃박스는 ①의 `StatementEstimated` 행만 증가 | Oracle IT: 표마다 전후 스냅샷 해시 |
| C2 | **예상 = 실제**: 어떤 달에 ①을 낸 뒤 **새 이벤트 없이** 마감·지급을 실행하면 `StatementEstimated`의 `gross`·`clawbackOffset`·`adjustment`·`withholdingTax`·`net` = `StatementConfirmed`+`PayoutCompleted`의 같은 필드(채권 상계 포함 케이스·정정 포함 케이스·음수 순액 채권화 케이스) | 골든 사이클 확장 |
| C3 | ① dry-run은 원장 락을 잡지 않는다(동시 계산과 경합 0 — Oracle IT에서 락 대기 없음) | IT |
| C4 | ② 결정론: 같은 입력 2회 → 같은 응답 바이트·같은 `ruleSnapshotId`; 룰 버전 하나 바뀌면 `ruleSnapshotId`가 바뀐다 | 단위 + IT |
| C5 | ② 응답이 포털 계약 `engine-internal.openapi.yaml` 스키마 통과(사본), 한도 초과·분급 커브 있음/없음·2026-06-30 이전 체결(한도 미적용)·해약 회차 경계 케이스 | 계약 시험 |
| C6 | ③ `CONFIRMED` = 발행된 이벤트 값(같은 달의 `StatementConfirmed`+`PayoutCompleted`와 필드 단위 동일), 마감 전 `CONFIRMED` 요청은 404 또는 계약이 정한 응답 | IT |
| C7 | ④ 조립 일치 시험(E1 `AssemblyParityTest`) 갱신, 해석기 우회 insert → 가드가 거부(시험 조립에서) | 단위·IT |
| C8 | ⑤ 클린 체크아웃에서 한 명령 → 피드가 A-1 스트림(⓪ 두 번째 판과 바이트 동일, `seq` 포함) | 스크립트 + CI(가능하면 — Oracle Free 컨테이너가 CI에서 돌면 잡 하나, 아니면 사용자 재현 기록) |
| C9 | ⓪ 두 판이 포털 계약 v1.2 검증 통과, 첫 판 ⊂ 두 번째 판(바이트) | `PortalA1ComparisonTest` 개정 |
| C10 | E1 무손상(전역 검증기 위반 0 — 타입 13종으로 증가), 골든 24+5 동일, jqwik 0, CI 2단, 주입 ID 단위 | CI 아티팩트 |

주입은 계획이 정하되 최소: dry-run이 한 행이라도 쓰게 → C1 실패; `ruleSnapshotId`를 시각으로 → C4 실패; ①에서 마감된 달 발행 → 실패; ③ `CONFIRMED`가 이벤트와 다른 값(상태 필터) → C6 실패; 가드 조립 제거 → C7 실패.

## 3. 계획에서 답할 질문

1. dry-run 구조 — 파이프라인·`PayoutService`를 어떻게 쓰기 없이 돌리나(저장소 포트의 인메모리 오버레이? 읽기 전용 트랜잭션 + 롤백? `settleOne`의 채권 `consume`는 상태를 바꾼다 — 예상은 어떻게 계산하나). **E1의 데코레이터가 dry-run에서 발행하지 않도록** 어떻게 끊나.
2. `ruleSnapshotId`의 정의와 재현 범위(룰 버전 집합만인가, 원장 상태도인가 — 포털 §2.3 "룰 스냅샷 ID 포함, 재현 가능").
3. `basisSeq`를 아웃박스 최대 부여 `seq`로 잡을 때, lazy 부여(E1 P8)와의 관계 — 산출 직전에 부여를 한 번 돌리나.
4. ③의 `CONFIRMED` 응답을 어디서 읽나(`AGENT_SETTLEMENT`+`PAYOUT_STATEMENT` 조합인가, 아웃박스 행인가 — 둘이 같아야 한다는 것이 C6).
5. ⑥을 넣을지 — 계약 안에서 가능한 범위와 비용.
6. ⑤의 Oracle Free 컨테이너가 CI에서 돌 수 있는지(현재 풀 빌드가 Testcontainers로 쓰고 있으므로 가능성 높음) — 되면 C8을 CI 잡으로.
7. 포털 계약 §4.3의 응답 필드 중 엔진이 줄 수 없는 것(`projectedAccum`·`incentives[].limitIncluded` 등)이 있으면 — E1 §0과 같은 방식으로 **포털 계약 완화 요청 목록**을 계획에(포털 PR이 먼저).

## 4. 하지 말 것

포털 계약 수정(요청 목록으로). 룰 데이터 하드코딩(세율·환수율·커브). 시뮬레이션 결과 영속(포털 몫). 레이트 리밋(포털 몫). 기존 Step 수정(B-12). 아웃박스 UPDATE/DELETE. ①을 마감된 달에.

## 5. 보고 형식

E1과 같게. 추가: C1 표별 전후 해시 표, C2 케이스별 대조 표, ⓪ 두 판의 해시, ⑤ 실행 명령과 소요, 포털에 넘길 질문(`StatementEstimated` 실 샘플 교체, ⑥ 계약 v2 항목).
