plugins {
    `java-library`
}

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))

    api(project(":commission-settlement"))
    api(project(":commission-recon"))

    // Spring Batch 5 배선 (설계서 §10 Phase 11) — 메타테이블은 Flyway로 관리한다 (자동 초기화 없음)
    api("org.springframework.batch:spring-batch-core")
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework:spring-tx")

    testImplementation(testFixtures(project(":commission-rule")))
    testImplementation(testFixtures(project(":commission-calc")))
    testImplementation(testFixtures(project(":commission-limit")))
    testImplementation(testFixtures(project(":commission-clawback")))
    testImplementation(testFixtures(project(":commission-deferral")))
    testImplementation(testFixtures(project(":commission-settlement")))
    // 배치 흐름 테스트용 잡 저장소: H2 + Spring Batch 동봉 스키마 (Flyway와 무관한 자립 구성)
    testImplementation("com.h2database:h2")
}
