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

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.12.2"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        "testImplementation"("org.assertj:assertj-core:3.27.3")
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
            "testFixturesApi"(platform("org.junit:junit-bom:5.12.2"))
            "testFixturesApi"("org.junit.jupiter:junit-jupiter-api")
            "testFixturesApi"("org.assertj:assertj-core:3.27.3")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("file.encoding", "UTF-8")
    }
}

// ---------------------------------------------------------------------------------------------
// 계약(Phase E3): contracts/는 ga-disclosure 저장소 contracts/의 복사본이다(정본은 저쪽). 이 저장소에서 계약을 고치지 않는다.
//  - contractChecksums       : contracts/CHECKSUMS 갱신(sha256sum 형식, 경로 정렬 — ga-disclosure와 같은 형식)
//  - verifyContractChecksums : CHECKSUMS가 파일과 다르면 실패(check에 연결)
//  - verifyUpstreamContract  : contracts/UPSTREAM(owner/repo@커밋)의 공개 원본을 받아 로컬 복사본과 SHA-256 대조(CI 전용 — 네트워크)
// ---------------------------------------------------------------------------------------------
val contractsDir = layout.projectDirectory.dir("contracts")

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun contractFiles(dir: File): List<File> = dir.walkTopDown()
    .filter { it.isFile && it.name != "CHECKSUMS" && it.name != "UPSTREAM" && it.name != ".DS_Store" }
    .toList()

fun contractChecksumText(dir: File): String = contractFiles(dir)
    .map { it.relativeTo(dir).invariantSeparatorsPath to sha256Hex(it.readBytes()) }
    .sortedBy { it.first }
    .joinToString("") { "${it.second}  ${it.first}\n" }

tasks.register("contractChecksums") {
    group = "contracts"
    description = "Writes contracts/CHECKSUMS (sha256sum format, sorted by path)."
    val dir = contractsDir.asFile
    doLast { dir.resolve("CHECKSUMS").writeText(contractChecksumText(dir)) }
}

val verifyContractChecksums = tasks.register("verifyContractChecksums") {
    group = "verification"
    description = "Fails when contracts/CHECKSUMS is stale."
    val dir = contractsDir.asFile
    inputs.dir(dir).withPathSensitivity(PathSensitivity.RELATIVE)
    doLast {
        val actual = dir.resolve("CHECKSUMS").takeIf { it.exists() }?.readText() ?: ""
        if (contractChecksumText(dir) != actual) {
            throw GradleException("contracts/CHECKSUMS 가 계약 파일과 다르다 — 계약은 ga-disclosure에서 복사하고 ./gradlew contractChecksums 로 갱신하라")
        }
    }
}

tasks.register("verifyUpstreamContract") {
    group = "verification"
    description = "Compares each contract file with ga-disclosure at the commit pinned in contracts/UPSTREAM (network)."
    val dir = contractsDir.asFile
    doLast {
        val pin = dir.resolve("UPSTREAM").readText().trim()
        val match = Regex("^([\\w.-]+/[\\w.-]+)@([0-9a-f]{40})$").matchEntire(pin)
            ?: throw GradleException("contracts/UPSTREAM must be owner/repo@<40-hex commit>: $pin")
        val (repo, commit) = match.destructured
        contractFiles(dir).forEach { f ->
            val path = f.relativeTo(dir).invariantSeparatorsPath
            val upstream = URI("https://raw.githubusercontent.com/$repo/$commit/contracts/$path").toURL().openStream().use { it.readBytes() }
            if (sha256Hex(upstream) != sha256Hex(f.readBytes())) {
                throw GradleException("contracts/$path differs from $repo@$commit — copy the upstream file, do not edit it here")
            }
            logger.lifecycle("contracts/$path == $repo@$commit (${sha256Hex(upstream)})")
        }
    }
}

tasks.named("check") {
    dependsOn(verifyContractChecksums)
}
