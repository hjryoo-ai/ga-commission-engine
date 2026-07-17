# GA 설계사 수수료 계산 시스템

[`docs/설계서.md`](docs/설계서.md)(v1.1.5, 단일 원본)의 구현. 룰은 코드가 아니라 데이터(유효기간 버전), 계산 결과는 불변 원장(취소분개로만 정정), 1200% 한도는 지급 파이프라인의 게이트.

## 빌드/테스트

```bash
./gradlew build          # 전 모듈 빌드 + 테스트 + Oracle 통합테스트 (Docker 필요)
./gradlew :commission-limit:test                    # 모듈 단위
./gradlew :commission-infra:integrationTest         # Oracle(Testcontainers)만
./gradlew build -x :commission-infra:integrationTest  # Docker 없는 환경 우회
```

- 도메인·엔진 모듈은 Spring/DB 무의존 순수 Java — 단위 테스트가 수 초 안에 돈다.
- commission-infra `test`는 H2(MODE=Oracle) 스키마 검증, `integrationTest`는
  **Testcontainers(gvenzl/oracle-free:23-slim-faststart)** 로 실제 Oracle에서
  계약 테스트·락·동시성 경합을 검증한다(§8.6~8.7). 로컬/CI 동일 명령.
- Flyway 위치: 공통 `db/migration` + Oracle 전용 `db/vendor/oracle`(파티셔닝, IS JSON,
  ACTIVE function-based unique index).

## 모듈 (의존 방향: api/batch → 엔진들 → rule → domain)

| 모듈 | 내용 |
|---|---|
| commission-domain | Money(BigDecimal, scale 0)/Rate/RoundingPolicy, 값객체, PolicyEvent |
| commission-rule | effective-dated RuleRepository(기준일 필수 규약), 요율 승인 워크플로(DRAFT→ACTIVE→SUPERSEDED), 시책 마스터(INCENTIVE_MST)·SpEL 조건식 엔진(SimpleEvaluationContext 샌드박스)·시책 승인 워크플로(요율과 동형 diff 감사) |
| commission-calc | 계산 파이프라인(Step 체인, Step 목록도 유효기간 설정), COMM_CALC 불변 원장 포트, Reversal&Rebook, replay |
| commission-limit | 1200% 한도 원장(LIMIT_LEDGER+DTL), LimitGateStep, 감액 재산정, 취소분개 원복 훅 |
| commission-clawback | 환수 Step(경과 회차 구간 환수율), 부활 재지급, 채권화·자동 상계 |
| commission-deferral | 분급 커브(데이터) 분할 Step(2027~), 도래 RELEASE 배치, 해약 시 소멸 |
| commission-settlement | 월마감 체크리스트 게이트, 원천세 3.3%, ADJUSTMENT(익월 귀속), 마감월 불변성 가드 |
| commission-inbound | 보험사 명세 어댑터(파일럿: SAMLIFE CSV), 멱등 저장 |
| commission-recon | 대사(명세 vs 자체계산) — 차이 유형화(MATCH/요율차/누락/미인지) |
| commission-api | 수수료 시뮬레이션(예상 수수료·한도 소진·분급 즉시분), 조회 파사드, REST 컨트롤러 슬라이스(조회/시뮬레이션/승인 — 순액은 NetAmountCalculator 경유, 금액 long·날짜 ISO 직렬화, fail-fast 예외 4xx 매핑, 승인 실승인자 필수). 풀 부트 조립은 '운영 조립' Phase로 이연 |
| commission-infra | Flyway 스키마(V1~V9 + Oracle 전용 V100/V101), MyBatis Oracle 어댑터(트랜잭션 Store 9종 + 룰 조회/승인/설계사 디렉토리 + 마감 리포트), 한도 원장 SELECT FOR UPDATE 직렬화 + posting_seq 락 하 채번, 승인 동시성 러너(인덱스 위반 → 재시도/거부, §6.6), 트리밍 감사 이력(COMM_RATE_CHANGE_HIST), OraclePersistence(트랜잭션 경계 §4.2 [3.5]~[5.5]) |
| commission-batch | Spring Batch 5 잡 배선 — 월마감/지급 런/분급 도래/재계산 4종. JobInstance 기반 요청 dedup, 항목 단위 트랜잭션(계산·지급 잡이 한도 락을 청크 전체에 걸쳐 잡지 않도록 Resourceless 스텝 TM + 반복당 업무 트랜잭션), 실패 격리(RuntimeException=격리·Error=전체 중단), BATCH_* 메타테이블 Flyway(V101) |

## 핵심 규약 (전 모듈 공통)

1. **double/float 금지.** 금액 산술은 `Money`, 반올림은 `RoundingPolicy` enum 외 사용 금지.
2. **룰 조회는 기준일 필수.** "현재 유효" 조회 API는 없다. 회차성 룰=업무 발생일, 계약성 룰(한도/분급/환수)=계약 체결일.
3. **COMM_CALC는 insert + 상태 전이만.** 정정은 음수 reversal(원본 참조) + rebook. REVERSED 원본은 합산에 남고 reversal이 상쇄한다.
   - **합산 규약(§3.0)**: 순액 = **전 상태 합산**, 상태 필터 SUM 금지, `NetAmountCalculator` 경유만. (상태 필터 SUM은 마감 전 정정 시 원본·reversal을 놓쳐 이중 차감을 낸다 — 컴포넌트 회귀 테스트로 박제.)
4. **마감(CLOSED) 월로의 신규 귀속 금지** — 자동으로 다음 OPEN 월 귀속. 마감 후 정정은 ADJUSTMENT.
5. **새 규제 = 새 Step**(stepId에 버전) + 유효기간 등록. 기존 Step은 수정하지 않는다.
6. **한도 락 규약(§6.1.6)**: 게이트 판정 전 원장 `SELECT FOR UPDATE` 획득, 저장 후 훅 전기·커밋까지 유지. posting_seq는 락 보유 상태에서만 채번, 정렬 기준은 posting_seq만(posted_at 금지).
7. **fail-fast(부록 B-13)**: 필수 룰 데이터(over_limit_action 등) 누락 시 침묵 기본값 대신 계산 거부. 정책 기본값을 코드에 심지 않는다.

## 테스트 (333건 = 단위/H2 237 + Oracle 통합 96)

- 골든 케이스: 신계약/회차/시책, 2026-06-30↔07-01 한도 경계, 한도 임박·초과·환수 복원, 13회차 전후 해약·철회·부활, 소급 요율 변경 reversal&rebook, 마감→지급 사이클, 분급 4년→7년 데이터 교체.
- Property(jqwik): Money 산술, 한도 불변식(accum ≤ limit, accum = ΣDTL), 분급 스케줄 합 = 이연 원금.
- replay: 계산 시점 원장 재구성 후 동일 금액 재산출, 소급 변경 검출.
- **계약 테스트(§8.7)**: 각 Store 포트의 추상 스위트(모듈 testFixtures `contract/`)를 인메모리 레퍼런스와 Oracle 어댑터가 동일하게 상속·통과 — 불변 원장, 멱등키, 상태 전이, 전기 순서(posting_seq), 빈 문자열=NULL 시맨틱.
- **동시성 경합(§8.6, Oracle 전용)**: 동일 원장 동시 계산 2건 — 뒤진 트랜잭션이 갱신된 누적을 보고 삭감됨(accum ≤ limit 멀티스레드 성립), 신규 원장 동시 생성 uq_limit 재시도, 저장 후 훅 실패 시 전체 롤백(원자성), FOR UPDATE 커밋까지 대기.
- **룰 마스터 계약(Phase 10b)**: 유효기간 경계 박제(요율 apply_from 당일 = 신 버전, 등급/소속 변경일 당일 = 신 버전), 겹치는 ACTIVE → Ambiguous fail-fast, 환수 테이블 상품별→공통 폴백, "기준일 필수" 위반 API 부재(리플렉션 검사), 트리밍 감사 이력(전후 값·승인자·시각).
- **승인 동시성(§6.6, Oracle 전용)**: 같은 키 동시 승인 2건 — 앱 계층 검사를 둘 다 통과해도 ux_comm_rate_active가 최종 심판, 패자는 재시도(최신 재조회·재검증)로 SUPERSEDE 수렴, 겹치는 ACTIVE 0건. 결정적 인터리빙 + 배리어 경합 2종.
- **배치 잡(Phase 11)**: H2 흐름(commission-batch)으로 dedup·게이트·강제 마감·격리·재시작 시맨틱을, 실 Oracle IT(commission-infra)로 완료 기준을 검증.
  - **중단·재시작 값 멱등성(실 Oracle)**: 재계산 4건 중 3번째에서 `Error`로 kill → 잡 FAILED, 중단 항목은 [3.5]~[5.5] 통째 롤백(부분 reversal 없음). 같은 request_id 재제출=재시작 → 진행 오프셋부터 재개, 전 정책 순액 동일·세대 정확히 1회분(수급자당 12레코드)·원장 불변식 성립, 완료 후 재제출은 no-op.
  - **지급 런 2회 반복(상계·원천세)**: 런1 A 정산 실패 주입 → 상계 이후 지점 롤백으로 채권 잔액 원복, 조직만 정산. 런2에서 A 수습(순액 전액 채권 상계 → 지급 0). 익월 런에서 잔여 채권 마저 상계, 원천세는 상계 후 순지급액 기준으로 런 단위 계산(3.3% 절사, 스텝 컨텍스트로 합계 검증).
  - **마감 게이트·강제 마감·리포트 3종(실 Oracle)**: PENDING 존재 시 잡 FAILED(월 OPEN), 강제 마감은 사유가 BATCH_JOB_EXECUTION_CONTEXT에 영속. 리포트 = 룰 완결성(겹치는 ACTIVE·마스터 누락) / MAXVALUE 파티션 적재 / 승인 경합(ACTIVATE 직후 SUPERSEDE)을 실 검출(비차단).

- **REST 컨트롤러 슬라이스(Phase 12)**: MockMvc standalone으로 파사드 위 얇은 컨트롤러 검증. **순액 API가 reversal 시나리오(마감 전 정정 3종 공존)에서 정확**(NetAmountCalculator 경유 → 상태 필터 이중 차감 −135,000 재현 안 됨), 금액 long·마감월 yyyyMM·날짜 ISO 직렬화 고정, fail-fast 예외의 4xx 매핑(내부 상세 비노출), 승인 API 실승인자 필수·"system" 거부. 상태 필터 SUM 부재는 소스 스캔 아키텍처 테스트로 고정(§3.0).

- **시책 조건식 엔진(Phase 13)**: SpEL 조건식을 **SimpleEvaluationContext(데이터 바인딩 전용) 샌드박스**에서만 평가 — `T()`·빈·생성자·메서드 호출·대입 거부를 금지 구문 테스트 20종으로 고정(관리자 입력이 코드 실행 통로가 되지 않음). 조건식 등록만으로 신규 시책 반영(재배포 없음), 요율 승인 패턴 재사용(트리밍/SUPERSEDE·diff 감사·겹침 불변식, 인메모리+Oracle 계약), IncentiveV2(30)<LimitGate(50) 순서로 한도 편입, 사용 시책 버전 rule_versions 박제·판정 calc_trace 기록(replay), 등록·평가 fail-fast.

설계 판단 근거와 잔여 과제는 `../설계고찰.md` 참고 (Phase 11 완료 기록은 §8, Phase 13은 §10).
