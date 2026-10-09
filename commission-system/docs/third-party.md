# 서드파티 의존성 — Boot BOM 밖 목록과 OSV 조회

> E3.2 항목 5(2026-10-09). **버전은 올리지 않았다** — 취약점은 보고만 한다(지시). 다음 점검 때 이 표를 다시 만든다.

## 조사 방법

- 이 저장소에는 Gradle 의존성 **락 파일이 없다**(지시의 전제와 다름). 무결성은 `gradle/verification-metadata.xml`(dependency verification)이 맡는다. 그래서 "락 파일의 외부 의존성" 대신 **모든 모듈의 해석된 런타임 클래스패스**를 열거했다 — 구성 `runtimeClasspath`·`productionRuntimeClasspath`(부트 jar)·`testRuntimeClasspath`·`testFixturesRuntimeClasspath`·`integrationTestRuntimeClasspath`, 14 모듈. 외부 컴포넌트 117개.
- "Boot BOM 안" = `org.springframework.boot:spring-boot-dependencies:3.5.16`의 `dependencyManagement`(가져온 BOM — jackson·junit·testcontainers 등 — 을 재귀로 펼친 1,478 좌표)에 있는 좌표. 해석된 버전이 BOM 버전과 같으면 "BOM 안", 다르면 아래 2절.
- OSV: `https://api.osv.dev/v1/querybatch`(생태계 `Maven`), 2026-10-09T12:59Z 조회. 대상은 1절 19개 + 2절 4개. BOM 자체(`spring-boot-dependencies`·`jackson-bom`·`junit-bom`)는 플랫폼이라 제외.

## 1. Boot BOM 밖 (19)

| 좌표 | 쓰임 | 들여온 곳 | OSV |
|---|---|---|---|
| `io.github.erdtman:java-json-canonicalization:1.1` | **런타임**(부트 jar) | 직접 — `commission-disclosure`(RFC 8785 JCS) | 없음 |
| `org.mybatis:mybatis:3.5.19` | **런타임** | 직접 — `commission-infra` | 없음 |
| `org.mybatis:mybatis-spring:3.0.6` | **런타임** | 직접 — `commission-infra` | 없음 |
| `org.hdrhistogram:HdrHistogram:2.2.2` | **런타임** | `io.micrometer:micrometer-core:1.15.12`(BOM) 경유 | 없음 |
| `org.latencyutils:LatencyUtils:2.0.3` | **런타임** | 같은 경로 | 없음 |
| `com.networknt:json-schema-validator:1.5.9` | 시험 | 직접 — `commission-disclosure` 계약 시험 | 없음 |
| `com.ethlo.time:itu:1.14.0` | 시험 | networknt 경유 | 없음 |
| `org.apache.commons:commons-compress:1.24.0` | 시험(`commission-infra` 통합 시험·`commission-app` 시험) | `org.testcontainers:testcontainers:1.21.4`(BOM) 경유 | **GHSA-4265-ccf5-phj5**(CVE-2024-26308, 중간), **GHSA-4g9r-vxhx-9pgx**(CVE-2024-25710, 중간) — 아래 3절 |
| `com.github.docker-java:docker-java-api:3.4.2` | 시험 | testcontainers 경유 | 없음 |
| `com.github.docker-java:docker-java-transport:3.4.2` | 시험 | testcontainers 경유 | 없음 |
| `com.github.docker-java:docker-java-transport-zerodep:3.4.2` | 시험 | testcontainers 경유 | 없음 |
| `net.java.dev.jna:jna:5.13.0` | 시험 | docker-java-transport-zerodep 경유 | 없음 |
| `org.rnorth.duct-tape:duct-tape:1.0.8` | 시험 | testcontainers 경유 | 없음 |
| `org.jetbrains:annotations:17.0.0` | 시험 | testcontainers 경유 | 없음 |
| `com.vaadin.external.google:android-json:0.0.20131108.vaadin1` | 시험 | `org.skyscreamer:jsonassert:1.5.3`(spring-boot-starter-test) 경유 | 없음 |
| `net.minidev:accessors-smart:2.5.2` | 시험 | `net.minidev:json-smart:2.5.2` 경유 | 없음 |
| `org.ow2.asm:asm:9.7.1` | 시험 | accessors-smart 경유 | 없음 |
| `org.objenesis:objenesis:3.3` | 시험 | `org.mockito:mockito-core:5.17.0` 경유 | 없음 |
| `org.opentest4j:opentest4j:1.3.0` | 시험 | JUnit 경유 | 없음 |

## 2. BOM이 관리하지만 다른 버전으로 해석되는 것 (4)

| 좌표(해석) | BOM 버전 | 어디서 | 이유 | OSV |
|---|---|---|---|---|
| `org.assertj:assertj-core:3.27.3` | 3.27.7 | 시험 — `commission-domain`·`commission-inbound`·`commission-recon`(Boot 플랫폼을 들이지 않는 모듈)과 `commission-app`(io.spring.dependency-management는 직접 선언 버전을 존중한다). 나머지 11개 모듈은 플랫폼 제약으로 3.27.7로 해석된다 | 루트 `build.gradle.kts`가 `assertj-core:3.27.3`을 직접 선언 | **GHSA-rqfh-9r24-8c9r**(CVE-2026-24400, 높음) — 아래 3절 |
| `net.bytebuddy:byte-buddy:1.15.11` | 1.17.8 | 시험 — `commission-domain`·`commission-inbound`·`commission-recon` | assertj 3.27.3의 의존 | 없음 |
| `net.bytebuddy:byte-buddy:1.18.3` | 1.17.8 | 시험 — 플랫폼 모듈 | assertj 3.27.7의 의존이 BOM보다 높다 | 없음 |
| `org.yaml:snakeyaml:2.5` | 2.4 | 시험 — `commission-disclosure`(계약 YAML 읽기) | `jackson-dataformat-yaml:2.21.4`의 의존 | 없음 |

## 3. 취약점 — 보고만(버전 올림 없음)

| 권고 | 대상 | 고친 버전 | 이 저장소에서의 노출 |
|---|---|---|---|
| GHSA-rqfh-9r24-8c9r / CVE-2026-24400 — AssertJ `isXmlEqualTo`가 신뢰할 수 없는 XML을 파싱할 때 XXE | `assertj-core:3.27.3`(시험 클래스패스만) | 3.27.7(= Boot 3.5.16 BOM 버전) | 낮음 — 저장소에 `isXmlEqualTo` 호출 0건(2026-10-09 소스 검색), 시험 입력은 저장소 안 데이터. 해소 경로: 루트의 직접 버전 선언을 BOM 버전으로 맞추는 것 — **승인 필요**(버전 변경) |
| GHSA-4265-ccf5-phj5 / CVE-2024-26308 — Pack200 손상 파일에서 OutOfMemoryError | `commons-compress:1.24.0`(시험만) | 1.26.0 | 낮음 — testcontainers가 이미지·파일 전송에 쓴다. 입력은 공식 이미지(`gvenzl/oracle-free`)와 저장소 파일. 운영 jar에 없다 |
| GHSA-4g9r-vxhx-9pgx / CVE-2024-25710 — 손상된 DUMP 파일에서 무한 루프 | 같음 | 1.26.0 | 같음 |

운영 jar(`productionRuntimeClasspath`)의 BOM 밖 의존 5개(JCS·MyBatis 둘·HdrHistogram·LatencyUtils)에는 OSV 결과가 없다.
