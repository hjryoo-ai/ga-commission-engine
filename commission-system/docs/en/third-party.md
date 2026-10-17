> 🌐 [한국어](../third-party.md) · **English** — translation of `commission-system/docs/third-party.md` at commit `48cc90f`. The Korean original is canonical; where the two differ, the Korean text prevails.

# Third-party dependencies — list outside the Boot BOM and OSV lookup

> E3.2 item 5 (2026-10-09). **Versions were not bumped** — vulnerabilities are only reported (per the directive). This table is rebuilt at the next check.
>
> **Reflecting E3.2 acceptance review §3 (same day)**: the direct declaration `assertj-core:3.27.3` in the root `build.gradle.kts` was removed so the Boot BOM (3.27.7) manages it — not a version bump but a return to BOM management. The 3 modules that did not bring in the Boot platform (domain · inbound · recon) get the same BOM **in test configurations only** (constraints only, no added dependencies). Result: the test classpath of every module has assertj 3.27.7, GHSA-rqfh-9r24-8c9r **resolved**. The runtime and boot-jar classpaths (`runtimeClasspath` · `productionRuntimeClasspath`) are identical before and after the change (resolution results compared). The assertj and byte-buddy 1.15.11 rows in the tables below are records from **before** the change.

## Method

- This repository has **no Gradle dependency lock files** (contrary to the directive's premise). Integrity is handled by `gradle/verification-metadata.xml` (dependency verification). So instead of "external dependencies in lock files", **the resolved runtime classpaths of all modules** were enumerated — configurations `runtimeClasspath` · `productionRuntimeClasspath` (boot jar) · `testRuntimeClasspath` · `testFixturesRuntimeClasspath` · `integrationTestRuntimeClasspath`, 14 modules. 117 external components.
- "Inside the Boot BOM" = coordinates present in the `dependencyManagement` of `org.springframework.boot:spring-boot-dependencies:3.5.16` (1,478 coordinates after recursively expanding imported BOMs — jackson, junit, testcontainers, etc.). If the resolved version equals the BOM version it is "inside the BOM"; if different, section 2 below.
- OSV: `https://api.osv.dev/v1/querybatch` (ecosystem `Maven`), queried 2026-10-09T12:59Z. Targets: the 19 in section 1 + the 4 in section 2. The BOMs themselves (`spring-boot-dependencies` · `jackson-bom` · `junit-bom`) are platforms and excluded.

## 1. Outside the Boot BOM (19)

| Coordinate | Usage | Brought in by | OSV |
|---|---|---|---|
| `io.github.erdtman:java-json-canonicalization:1.1` | **Runtime** (boot jar) | Direct — `commission-disclosure` (RFC 8785 JCS) | None |
| `org.mybatis:mybatis:3.5.19` | **Runtime** | Direct — `commission-infra` | None |
| `org.mybatis:mybatis-spring:3.0.6` | **Runtime** | Direct — `commission-infra` | None |
| `org.hdrhistogram:HdrHistogram:2.2.2` | **Runtime** | Via `io.micrometer:micrometer-core:1.15.12` (BOM) | None |
| `org.latencyutils:LatencyUtils:2.0.3` | **Runtime** | Same path | None |
| `com.networknt:json-schema-validator:1.5.9` | Test | Direct — `commission-disclosure` contract tests | None |
| `com.ethlo.time:itu:1.14.0` | Test | Via networknt | None |
| `org.apache.commons:commons-compress:1.24.0` | Test (`commission-infra` integration tests · `commission-app` tests) | Via `org.testcontainers:testcontainers:1.21.4` (BOM) | **GHSA-4265-ccf5-phj5** (CVE-2024-26308, moderate), **GHSA-4g9r-vxhx-9pgx** (CVE-2024-25710, moderate) — section 3 below |
| `com.github.docker-java:docker-java-api:3.4.2` | Test | Via testcontainers | None |
| `com.github.docker-java:docker-java-transport:3.4.2` | Test | Via testcontainers | None |
| `com.github.docker-java:docker-java-transport-zerodep:3.4.2` | Test | Via testcontainers | None |
| `net.java.dev.jna:jna:5.13.0` | Test | Via docker-java-transport-zerodep | None |
| `org.rnorth.duct-tape:duct-tape:1.0.8` | Test | Via testcontainers | None |
| `org.jetbrains:annotations:17.0.0` | Test | Via testcontainers | None |
| `com.vaadin.external.google:android-json:0.0.20131108.vaadin1` | Test | Via `org.skyscreamer:jsonassert:1.5.3` (spring-boot-starter-test) | None |
| `net.minidev:accessors-smart:2.5.2` | Test | Via `net.minidev:json-smart:2.5.2` | None |
| `org.ow2.asm:asm:9.7.1` | Test | Via accessors-smart | None |
| `org.objenesis:objenesis:3.3` | Test | Via `org.mockito:mockito-core:5.17.0` | None |
| `org.opentest4j:opentest4j:1.3.0` | Test | Via JUnit | None |

## 2. Managed by the BOM but resolved to a different version (4)

| Coordinate (resolved) | BOM version | Where | Reason | OSV |
|---|---|---|---|---|
| `org.assertj:assertj-core:3.27.3` | 3.27.7 | Test — `commission-domain` · `commission-inbound` · `commission-recon` (modules that do not bring in the Boot platform) and `commission-app` (io.spring.dependency-management respects directly declared versions). The other 11 modules resolve to 3.27.7 through the platform constraint | The root `build.gradle.kts` directly declares `assertj-core:3.27.3` | **GHSA-rqfh-9r24-8c9r** (CVE-2026-24400, high) — section 3 below |
| `net.bytebuddy:byte-buddy:1.15.11` | 1.17.8 | Test — `commission-domain` · `commission-inbound` · `commission-recon` | Dependency of assertj 3.27.3 | None |
| `net.bytebuddy:byte-buddy:1.18.3` | 1.17.8 | Test — platform modules | The dependency of assertj 3.27.7 is higher than the BOM | None |
| `org.yaml:snakeyaml:2.5` | 2.4 | Test — `commission-disclosure` (reading contract YAML) | Dependency of `jackson-dataformat-yaml:2.21.4` | None |

## 3. Vulnerabilities — report only (no version bump)

| Advisory | Target | Fixed version | Exposure in this repository |
|---|---|---|---|
| GHSA-rqfh-9r24-8c9r / CVE-2026-24400 — XXE when AssertJ `isXmlEqualTo` parses untrusted XML | `assertj-core:3.27.3` (test classpath only) | 3.27.7 (= Boot 3.5.16 BOM version) | **Resolved (E3.2 review §3)** — direct version declaration removed, 3.27.7 in all modules. Even before, there were 0 calls to `isXmlEqualTo` |
| GHSA-4265-ccf5-phj5 / CVE-2024-26308 — OutOfMemoryError on corrupted Pack200 files | `commons-compress:1.24.0` (test only) | 1.26.0 | Low — testcontainers uses it for image and file transfer. Inputs are an official image (`gvenzl/oracle-free`) and repository files. Not in the production jar |
| GHSA-4g9r-vxhx-9pgx / CVE-2024-25710 — infinite loop on corrupted DUMP files | Same | 1.26.0 | Same |

**commons-compress waits** (E3.2 review §3): test-only, outside the production jar. The version is not forced — forcing it would make this repository responsible for guaranteeing compatibility with testcontainers. **When the testcontainers version changes (Boot BOM update), re-check these two advisories (GHSA-4265-ccf5-phj5 · GHSA-4g9r-vxhx-9pgx).**

The 5 non-BOM dependencies in the production jar (`productionRuntimeClasspath`) (JCS · the two MyBatis artifacts · HdrHistogram · LatencyUtils) have no OSV results.
