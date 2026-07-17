# 기여 가이드 (저장소 협업 규약)

이 규약은 지금까지 대화로 지켜온 원칙을 저장소에 정착시킨 것이다. 코드보다 이 규약이 먼저다.

## 단일 저장소 (분리 금지)

이 프로젝트의 밀도는 세 가지에서 나온다 — **① 정본 문서(`commission-system/docs/설계서.md`)가
코드와 같은 커밋으로 움직인다, ② 계약 테스트(testFixtures `contract/`)가 모듈을 가로질러 인메모리
레퍼런스와 Oracle 어댑터를 동시에 검증한다, ③ 변경은 원자 단위로 커밋된다.** 모듈별·문서별 저장소
분리는 이 셋을 전부 깬다(Gradle 12모듈은 이미 하나의 빌드이고, 계약 스위트 공유엔 아티팩트 배포가
필요해진다). 문서·골든셋 CSV·`gradle/verification-metadata.xml`까지 전부 한 저장소다. 권한 분리가
필요하면(예: 정산 담당자의 골든셋 CSV 편집) 저장소가 아니라 **경로 단위 리뷰 권한(CODEOWNERS)**으로 푼다.

## 브랜치 — 트렁크 기반

- **`main` 보호**: 직접 푸시 금지, PR 필수, CI green 필수.
- 작업은 단명 브랜치(`feature/inbound-samsung`, `fix/…`)에서. PR은 혼자여도 만든다 —
  diff 리뷰가 PR 리뷰로, Phase 보고가 PR 설명으로 자연 승격된다.
- 커밋은 작업 단위로 잘게, **머지는 squash**로 "PR = 논리적 변경 1개"를 유지한다.

## 버전·태그 (이원화)

- **`phase-N` 태그**: 구축 이력. 그대로 동결한다(phase-12 ~ phase-16-final).
- **릴리스 태그(semver)**: 배포 산출물의 이력. 가동 전까지 `v0.x.y`, **섀도 런 1~2 마감 주기
  통과(go-live 게이트)를 `v1.0.0`**으로. 릴리스 노트에 "설계서 vX.Y 기준"을 명시해 연결한다.
- **문서 버전(`docs/설계서.md` v1.2.x)**: 문서 변경 이력. 릴리스 버전과 별개다.

## CI (2단, `.github/workflows/ci.yml`)

- **모든 푸시/PR**: 빠른 티어 — 단위/H2 + 아키텍처/골든셋(Docker 불필요).
- **PR + main 머지**: 풀 빌드 — Oracle Testcontainers IT + 기동 스모크(Docker).
- **dependency verification은 CI에서도 항상 활성**(wrapper가 강제). 의존성 추가/metadata 변경 PR은
  재생성 diff 리뷰(의도 외 아티팩트 0)를 **PR 체크리스트 항목**으로 둔다.

## 문서-코드 동기화 (머지 게이트)

main에 머지되는 모든 PR은 스스로 답한다 — **"이 변경이 설계서에 반영될 사항인가? 아니오면 왜?"**
(PR 템플릿의 필수 항목). 설계 판단이 바뀌면 같은 PR에서 정본을 갱신하고, 근거는 `설계고찰.md`에 남긴다.

## 부록 B 핵심 규약 (전 모듈)

double/float 금지 · 순액은 `NetAmountCalculator` 경유(§3.0) · 룰 조회 기준일 필수 · 새 규제는 새 Step ·
fail-fast(침묵 기본값 금지) · 비밀 0(외부 구성 주입). 상세는 `commission-system/docs/설계서.md` 부록 B.
