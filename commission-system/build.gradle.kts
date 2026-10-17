import java.net.URI
import java.security.MessageDigest

plugins {
    java
    id("org.springframework.boot") version "3.5.16" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "ga.comm"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    // 시험 라이브러리 버전은 Boot BOM이 정한다(E3.2 심사 §3): assertj를 직접 버전(3.27.3, CVE-2026-24400)으로 선언하던 것을 지우고,
    // Boot 플랫폼을 들이지 않는 모듈(domain·inbound·recon)도 시험 구성에만 같은 BOM을 둔다 — 버전 제약만 더하고 의존은 더하지 않는다.
    dependencies {
        "testImplementation"(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
        "testImplementation"(platform("org.junit:junit-bom:5.12.2"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        "testImplementation"("org.assertj:assertj-core")
    }

    // net.jqwik 차단(Phase E3-0): jqwik 1.10부터 AI 코딩 에이전트 사용 배제 조항과 테스트 출력 지시문 삽입을 가진다.
    // 속성 테스트는 JUnit 5 @ParameterizedTest + 시드 고정 생성기(commission-domain testFixtures SeededCases)로 쓴다.
    // 직접·전이 의존 어느 경로로든 해석되면 그 구성의 해석이 실패한다.
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "net.jqwik") {
                throw GradleException("net.jqwik is banned (Phase E3-0): ${requested.group}:${requested.name}:${requested.version}")
            }
        }
    }

    // 계약 테스트(Store 포트 동작 동등성 스위트, 설계서 §8.7)는 testFixtures에 추상 클래스로 두고
    // 인메모리 레퍼런스와 Oracle 어댑터가 같은 스위트를 상속한다.
    plugins.withId("java-test-fixtures") {
        dependencies {
            "testFixturesApi"(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
            "testFixturesApi"(platform("org.junit:junit-bom:5.12.2"))
            "testFixturesApi"("org.junit.jupiter:junit-jupiter-api")
            "testFixturesApi"("org.assertj:assertj-core")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("file.encoding", "UTF-8")
        // 전역 검증기 집계(Phase E1 C1): 아웃박스 시험 픽스처의 리스너가 태스크 끝에 쓴다 — CI 산출물 test-reports(test-results/**/*.xml)에 담긴다.
        val validationFile = layout.buildDirectory.file("test-results/outbox-validation/${name}.xml")
        outputs.file(validationFile).optional()
        systemProperty("outbox.validation.file", validationFile.get().asFile.absolutePath)
    }
}

// ---------------------------------------------------------------------------------------------
// 계약(Phase E3 → E1 D-16): contracts/shared/<업스트림>/은 그 저장소 contracts/의 복사본이다(정본은 저쪽). 이 저장소에서 계약을 고치지 않는다.
//  - ga-disclosure    : 등급 API(engine-disclosure.openapi.yaml)와 공유 Envelope 핵심(envelope-core.schema.json)
//  - ga-agent-portal  : 포털 이벤트 계약(events/v1/**)과 피드 OpenAPI(engine-internal.openapi.yaml)
// 디렉터리마다 CHECKSUMS(sha256sum 형식, 경로 정렬)와 UPSTREAM(첫 줄 owner/repo@<40자 병합 커밋>, 다음 줄부터 사본 파일 목록 — 닫힌 목록)을 둔다.
//  - contractChecksums       : 디렉터리마다 CHECKSUMS 갱신
//  - verifyContractChecksums : CHECKSUMS가 파일과 다르거나 사본 파일 집합이 UPSTREAM 목록과 다르면 실패(check에 연결, 오프라인)
//  - verifyUpstreamContract  : UPSTREAM 커밋의 공개 원본(contracts/<경로>)을 받아 사본과 바이트 대조(CI — 네트워크). 실패 메시지에 두 해시
// ---------------------------------------------------------------------------------------------
val sharedContractsDir = layout.projectDirectory.dir("contracts/shared")

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun upstreamDirs(root: File): List<File> =
    root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }

fun contractFiles(dir: File): List<File> = dir.walkTopDown()
    .filter { it.isFile && it.name != "CHECKSUMS" && it.name != "UPSTREAM" && it.name != ".DS_Store" }
    .toList()

fun contractChecksumText(dir: File): String = contractFiles(dir)
    .map { it.relativeTo(dir).invariantSeparatorsPath to sha256Hex(it.readBytes()) }
    .sortedBy { it.first }
    .joinToString("") { "${it.second}  ${it.first}\n" }

data class UpstreamPin(val repo: String, val commit: String, val files: Set<String>)

fun upstreamPin(dir: File): UpstreamPin {
    val lines = dir.resolve("UPSTREAM").readLines().map { it.trim() }.filter { it.isNotEmpty() }
    val match = Regex("^([\\w.-]+/[\\w.-]+)@([0-9a-f]{40})$").matchEntire(lines.firstOrNull() ?: "")
        ?: throw GradleException("${dir.name}/UPSTREAM 첫 줄은 owner/repo@<40자 커밋>이어야 한다: ${lines.firstOrNull()}")
    val (repo, commit) = match.destructured
    return UpstreamPin(repo, commit, lines.drop(1).toSortedSet())
}

tasks.register("contractChecksums") {
    group = "contracts"
    description = "Writes CHECKSUMS in each contracts/shared/<upstream>/ (sha256sum format, sorted by path)."
    val root = sharedContractsDir.asFile
    doLast { upstreamDirs(root).forEach { it.resolve("CHECKSUMS").writeText(contractChecksumText(it)) } }
}

val verifyContractChecksums = tasks.register("verifyContractChecksums") {
    group = "verification"
    description = "Fails when a contracts/shared/<upstream>/CHECKSUMS is stale or the copied file set differs from its UPSTREAM list."
    val root = sharedContractsDir.asFile
    inputs.dir(root).withPathSensitivity(PathSensitivity.RELATIVE)
    doLast {
        val dirs = upstreamDirs(root)
        if (dirs.map { it.name } != listOf("ga-agent-portal", "ga-disclosure")) {
            throw GradleException("contracts/shared/ 업스트림 디렉터리는 ga-agent-portal·ga-disclosure 둘이다: ${dirs.map { it.name }}")
        }
        dirs.forEach { dir ->
            val actual = dir.resolve("CHECKSUMS").takeIf { it.exists() }?.readText() ?: ""
            if (contractChecksumText(dir) != actual) {
                throw GradleException("contracts/shared/${dir.name}/CHECKSUMS 가 계약 파일과 다르다 — 계약은 업스트림에서 복사하고 ./gradlew contractChecksums 로 갱신하라")
            }
            val present = contractFiles(dir).map { it.relativeTo(dir).invariantSeparatorsPath }.toSortedSet()
            val listed = upstreamPin(dir).files
            if (present != listed || listed.isEmpty()) {
                throw GradleException("contracts/shared/${dir.name} 사본 파일 집합이 UPSTREAM 목록과 다르다: 목록=$listed, 사본=$present")
            }
            logger.lifecycle("verifyContractChecksums: contracts/shared/${dir.name} — ${present.size} files match CHECKSUMS and UPSTREAM")
        }
    }
}

tasks.register("verifyUpstreamContract") {
    group = "verification"
    description = "Compares each copied contract file with its upstream repository at the commit pinned in UPSTREAM (network)."
    val root = sharedContractsDir.asFile
    doLast {
        upstreamDirs(root).forEach { dir ->
            val pin = upstreamPin(dir)
            pin.files.forEach { path ->
                val upstream = URI("https://raw.githubusercontent.com/${pin.repo}/${pin.commit}/contracts/$path").toURL().openStream().use { it.readBytes() }
                val local = dir.resolve(path).readBytes()
                val up = sha256Hex(upstream)
                val copy = sha256Hex(local)
                if (up != copy || !upstream.contentEquals(local)) {
                    throw GradleException("contracts/shared/${dir.name}/$path differs from ${pin.repo}@${pin.commit}:contracts/$path " +
                        "(copy sha256=$copy, upstream sha256=$up) — copy the upstream file, do not edit it here")
                }
                logger.lifecycle("contracts/shared/${dir.name}/$path == ${pin.repo}@${pin.commit} ($up)")
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyContractChecksums)
}
