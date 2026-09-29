plugins {
    `java-library`
}

// 통합테스트 소스셋 분리 (설계서 §8.7): test = H2 스키마 검증(빠름),
// integrationTest = Testcontainers Oracle 계약·락·동시성 검증. check에 포함되어
// 로컬/CI가 동일하게 ./gradlew build 한 번으로 전부 실행된다 (Docker 필수).
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

configurations["integrationTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))

    api(project(":commission-rule"))
    api(project(":commission-calc"))
    api(project(":commission-limit"))
    api(project(":commission-clawback"))
    api(project(":commission-deferral"))
    api(project(":commission-settlement"))
    api(project(":commission-inbound"))
    api(project(":commission-disclosure"))   // 비교설명 등급·순위 포트의 Oracle 어댑터(Phase E3)

    implementation("org.mybatis:mybatis:3.5.19")
    implementation("org.mybatis:mybatis-spring:3.0.6")
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework:spring-tx")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.flywaydb:flyway-core")

    testImplementation("com.h2database:h2")

    "integrationTestImplementation"(testFixtures(project(":commission-rule")))
    "integrationTestImplementation"(testFixtures(project(":commission-calc")))
    "integrationTestImplementation"(testFixtures(project(":commission-limit")))
    "integrationTestImplementation"(testFixtures(project(":commission-clawback")))
    "integrationTestImplementation"(testFixtures(project(":commission-deferral")))
    "integrationTestImplementation"(testFixtures(project(":commission-settlement")))
    "integrationTestImplementation"(testFixtures(project(":commission-inbound")))
    "integrationTestImplementation"(testFixtures(project(":commission-disclosure")))
    "integrationTestImplementation"(project(":commission-batch"))
    "integrationTestImplementation"(project(":commission-shadow")) // 섀도 런 실 Oracle 경로 IT

    "integrationTestImplementation"("org.testcontainers:oracle-free")
    "integrationTestImplementation"("com.zaxxer:HikariCP")
    "integrationTestRuntimeOnly"("com.oracle.database.jdbc:ojdbc11")
    "integrationTestRuntimeOnly"("org.flywaydb:flyway-database-oracle")
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Oracle(Testcontainers) 계약·락·동시성 통합테스트 (설계서 §8.6~§8.8)"
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(integrationTest)
}
