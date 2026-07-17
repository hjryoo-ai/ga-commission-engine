plugins {
    `java-library`
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// 운영 조립 모듈 (설계서 §10 Phase 16) — 실행 가능한 Spring Boot 앱.
// 스토어(infra)·서비스/컨트롤러(api)·배치(batch)·섀도(shadow)를 전 빈 배선하고, 구성은 외부화한다.
dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.16")
    }
}

dependencies {
    api(project(":commission-infra"))   // Oracle 어댑터 + OraclePersistence
    api(project(":commission-api"))      // 서비스·REST 컨트롤러
    api(project(":commission-batch"))    // 배치 잡 팩토리 + BatchRuntime
    api(project(":commission-shadow"))   // 섀도 런 서비스

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")

    runtimeOnly("com.oracle.database.jdbc:ojdbc11")
    runtimeOnly("org.flywaydb:flyway-database-oracle")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:oracle-free")
    testImplementation(testFixtures(project(":commission-rule")))
    testImplementation(testFixtures(project(":commission-calc")))
}
