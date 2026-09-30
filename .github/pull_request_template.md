<!-- PR = 논리적 변경 1개. 머지는 merge commit(squash 금지 — 보고서가 커밋 해시를 인용). 아래 체크리스트는 지금까지 대화에서 지켜온 규약의 정착지다. -->

## 무엇을 / 왜

<!-- 이 변경이 무엇을 하고 왜 필요한지 1~3문장. 관련 Phase/이슈가 있으면 링크. -->

## 문서 동기화 (필수 자문)

- [ ] **이 변경이 `commission-system/docs/설계서.md`(정본)에 반영될 사항인가?**
  - 예 → 같은 PR에서 설계서를 갱신했다(변경 이력 행 포함). 근거는 `설계고찰.md`.
  - 아니오 → 왜 아닌지 한 줄: <!-- 예: 순수 리팩터·테스트 보강으로 설계 판단 불변 -->

## 검증

- [ ] CI green (아래 참조). 기존 테스트 무손상 — 이관/대체가 있으면 명시.
- [ ] 새 동작은 테스트로 고정(계약 테스트는 인메모리+Oracle 양쪽).
- [ ] **의존성 추가/`gradle/verification-metadata.xml` 변경 시**: 재생성 diff를 검토해
      **의도한 아티팩트만** 추가됐음을 확인(공급망 리뷰). 신규/제거 컴포넌트를 아래에 요약:
      <!-- 예: +org.springframework.security:* (의도), 제거 0 -->

## 규약 확인

- [ ] double/float 금지·순액은 `NetAmountCalculator` 경유(§3.0)·룰 조회 기준일 필수·
      새 규제는 새 Step 등 부록 B 핵심 규약 위반 없음.
- [ ] 비밀 0 — 자격증명·접속정보는 커밋에 없다(외부 구성 주입).

<!--
CI (2단, .github/workflows/ci.yml):
  - 모든 푸시/PR: 빠른 티어(단위/H2 + 아키텍처/골든셋, Docker 없음)
  - PR + main 머지: 풀 빌드(Oracle Testcontainers IT + 기동 스모크)
main 직접 푸시 금지 — PR + CI green + 리뷰 후 merge commit 머지.
-->
