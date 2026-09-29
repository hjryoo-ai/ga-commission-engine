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
