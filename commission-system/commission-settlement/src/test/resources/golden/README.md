# 골든셋 — 정산 담당자 작성 가이드 (설계서 §8.1, Phase 14)

이 폴더의 CSV는 **정산 담당자와 계산 시스템 사이의 계약**입니다. 정산 담당자가 엑셀로 검증한 대표
케이스를 CSV로 두면, 계산 엔진이 매 빌드마다(CI 상시) 같은 결과를 내는지 자동 대조합니다. 하나라도
어긋나면 빌드가 실패합니다. **케이스 추가·수정에 개발자가 필요 없습니다 — CSV만 추가하면 됩니다.**

## 1. 케이스 하나 = 폴더 하나 (자기완결)

`cases/` 아래 폴더 하나가 케이스 하나입니다. 그 폴더 안의 CSV들이 **[룰 시드 + 사건 타임라인 + 기대
결과]를 전부** 담습니다. 다른 케이스나 공유 시드에 기대지 않습니다.

> **규정이 바뀌면 기존 케이스를 고치지 말고 새 케이스 폴더를 추가하세요.** (요율·룰이 유효기간으로
> 관리되는 것과 같은 철학입니다.) 기존 케이스는 "그때의 규정에서 이렇게 계산됐다"는 사실을 그대로
> 박제한 채 남고, 새 규정은 새 케이스로 검증합니다. 그래서 각 케이스가 자기 시드를 통째로 갖습니다.

## 2. 새 케이스 추가하는 법

1. `cases/_TEMPLATE` 폴더를 통째로 복사해 `cases/21_내케이스이름` 처럼 이름을 붙입니다.
   (앞이 `_`인 폴더는 실행에서 제외됩니다 — 템플릿·메모용.)
2. 아래 파일들을 엑셀로 열어 값을 채웁니다(안 쓰는 시드 파일은 지워도 됩니다).
3. `./gradlew :commission-settlement:test --tests ga.comm.golden.GoldenCsvRunnerTest` 로 확인합니다.
   불일치가 있으면 사람이 읽는 형태로 "무엇이 / 기대 얼마 vs 실제 얼마 / 원인 후보"를 출력합니다.

## 3. 공통 규약

- **인코딩**: UTF-8 (BOM 포함). 엑셀에서 "CSV UTF-8"로 저장하면 됩니다.
- **금액**: 원 단위 **정수**. 천단위 콤마·소수점·통화기호 없이 `1890000` 처럼 씁니다.
- **비율(요율/지급률/환수율)**: `7.0`, `0.9`, `0.15` 처럼 소수. (700% = `7.0`)
- **날짜**: `yyyy-MM-dd` (예: `2026-08-01`). **마감월/도래월**: `yyyyMM` (예: `202608`).
- **빈 칸**: 해당 항목 없음/생략. 선택 컬럼은 통째로 지워도 됩니다.
- **주석**: `#`로 시작하는 줄과 빈 줄은 무시됩니다.
- **값 안에 콤마를 쓰지 마세요.** 부가 속성은 세미콜론으로 구분합니다(아래 `attrs`).
- **features**: 이 케이스가 켜는 계산 기능. `case.csv`의 `features`에 세미콜론으로 나열합니다.
  - `LIMIT` — 1200% 한도 게이트·원장 (한도 삭감·감액 재산정)
  - `CLAWBACK` — 환수/부활 (한도 원장 연동을 포함하므로 LIMIT도 자동 포함)
  - `DEFERRAL` — 분급 분할·도래
  - 아무것도 없으면 기본 계산(모집수수료·지급률·시책·오버라이드)만 돕니다.

## 4. 파일별 스키마

### `case.csv` — 케이스 머리말 (`field,value` 세로 표)
`id`, `title`, `axis`(검증 축, 여러 개면 `;`), `features`, `insurer`, `product`, `agent`,
`premium`(기본 월납, 이벤트에서 생략 시 사용), `note`.

### 시드 파일 (필요한 것만 두면 됩니다)
| 파일 | 컬럼 | 뜻 |
|---|---|---|
| `seed_commtypes.csv` | `commType,limitIncluded,clawbackTarget,rounding,applyFrom` | 수수료 유형 속성(한도 포함·환수 대상 여부). `rounding`은 보통 `KRW_FLOOR` |
| `seed_rates.csv` | `commType,installmentNo,rate,applyFrom` | 모집/계속수수료율. `installmentNo` 빈 칸 = 성립(신계약). 보험사·상품은 `case.csv` 값 |
| `seed_payout.csv` | `grade,commType,rate,applyFrom` | 등급별 지급률 |
| `seed_overrides.csv` | `orgLevel,commType,rate,applyFrom` | 조직 오버라이드율. `orgLevel`=`TEAM`/`BRANCH`/`HQ` |
| `seed_limit.csv` | `channel,multiple,fyWindowMonths,overLimitAction,clawbackRestores,applyFrom` | 1200% 한도룰. `channel`=`GA_TO_AGENT`, `overLimitAction`=`DEFER_AFTER_FY`/`CUT` |
| `seed_deferral.csv` | `curveId,name,applyFrom,applyTo,monthOffset,ratio` | 분급 커브. **커브 하나를 여러 줄(포인트)로** 적습니다. `monthOffset` 0=즉시분 |
| `seed_clawback.csv` | `eventType,bandFrom,bandTo,rate,applyFrom` | 환수율. **구간(band)마다 한 줄.** `eventType`=`CANCEL`/`LAPSE`/`WITHDRAW` |
| `seed_agent.csv` | `agentId,grade,gradeFrom,team,branch,hq,orgFrom` | 설계사 등급·소속 체인(한 줄) |

### `timeline.csv` — 사건을 **순서대로** (위→아래 = 실행 순서)
`step` 컬럼이 무엇을 하는 줄인지 정하고, 나머지 컬럼은 step 유형에 따라 해석합니다.
**그 step이 안 쓰는 컬럼은 비워 둡니다.** 케이스마다 필요한 컬럼만 헤더에 넣어도 됩니다.

| step | 쓰는 컬럼 | 뜻 |
|---|---|---|
| `EVENT` | `ref,type,policyNo,eventDate,contractDate,installment,premium,payment,attrs` | 계약 사건 처리. `type`=`NEW`/`PAYMENT`/`REDUCE`/`CANCEL`/`LAPSE`/`WITHDRAW`/`REVIVE`. 해약류는 `installment`에 **경과 회차**를 적습니다 |
| `APPROVE_RATE` | `commType,installment,rate,applyFrom` | 소급 요율 승인(트리밍/SUPERSEDE). 정정 시나리오에서 씀 |
| `REBOOK` | `ref,targetRef,note` | `targetRef`가 가리키는 EVENT를 재계산(reversal + rebook) |
| `RELEASE` | `ref,dueYm` | 분급 도래분을 지급 파이프라인에 투입 |
| `DEDUCT_CLAWBACK` | `policyNo,eventDate,amount` | 환수분만큼 한도 원장 차감(복원) |

- `ref`는 그 단계의 산출물을 기대표에서 가리키기 위한 짧은 이름표(`E1`, `C1`, `R1` …)입니다.
- `attrs`는 이벤트 부가 속성을 `키=값;키=값`으로 적습니다:
  - `incentive_amount=1000000` — 이벤트성 시책 금액 → INCENTIVE 라인
  - `new_monthly_premium=100000` — 감액(REDUCE) 후 월납

### `expected_lines.csv` — 기대 커미션 라인
`event,recipientType,recipientId,commType,calcAmount,limitCut,closeYm`
(뒤 세 컬럼은 선택 — 빈 칸이면 그 항목은 비교하지 않음)
- `event`: 어느 단계의 산출물을 볼지. 타임라인의 `ref`(예 `E1`)를 적습니다.
  **`NET`으로 적으면** 그 (수급자×유형)의 **전 상태 합산**(원본·reversal·rebook 모두 더한 순액)을
  봅니다 — 마감 전 정정처럼 여러 세대가 공존하는 케이스에 씁니다.
- `recipientType`=`AGENT`/`ORG`, `recipientId`=설계사ID 또는 조직ID(`T1`/`B1`/`H1`).
- `commType`=`FY_COMM`/`RENEWAL`/`INCENTIVE`/`OVERRIDE`/`DEFERRED`/`CLAWBACK`(환수는 음수).
- 여기 **적은 라인만** 검사합니다(부분집합). 적지 않은 라인이 더 나와도 실패하지 않습니다.

### `expected_ledger.csv` — 기대 한도 원장 (최종 누적, 선택)
`policyNo,agentId,limitAmount,accumPaid,available,fyStart,fyEnd`
빈 칸인 컬럼은 비교하지 않습니다.

### `expected_schedule.csv` — 기대 분급 스케줄 (최종 상태, 선택)
`dueYm,amount,status,payCondition` — `status`=`SCHEDULED`/`RELEASED`/`HELD`/`CANCELLED`,
`payCondition`은 보통 `POLICY_INFORCE`. **여기 적으면 스케줄 전체를 집합으로 정확히 대조**합니다
(기대에 없는 실제 엔트리도 실패).

## 5. 기대값 팁

- **"무언가가 없어야 한다"를 검증할 때**: 부재는 라인으로 직접 적기 어렵습니다. 대신 관측 가능한
  결과로 증명하세요. 예) "13회차 해약은 환수가 없다" → `expected_ledger`의 `accumPaid`가
  기지급 그대로임을 적습니다(환수가 있었다면 줄었을 값).
- **한도 삭감**: `limitCut`에 삭감액을 적고 `calcAmount`에 실제 지급액을 적습니다. 전액 이연이면
  `calcAmount=0`, `limitCut`에 원래 금액.
- 값이 틀리면 러너가 원인 후보(요율 시드값·한도·반올림 등)를 함께 출력하니 거기서부터 좁히세요.

## 6. 지금 편성된 케이스

`cases/` 아래를 참고 예시로 쓰세요. 신계약·회차·시책·한도 경계/임박/초과·감액·환수(해약/실효/철회/부활)·
분급(2027 4년 / 2029 7년 / 도래)·마감 전 소급정정까지 §8.1 대표 축을 덮고 있습니다. 빌드 로그에
`[골든셋] 케이스 N건 — 커버 축: …` 요약이 찍힙니다.
