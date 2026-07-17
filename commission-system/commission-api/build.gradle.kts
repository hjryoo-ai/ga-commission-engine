plugins {
    `java-library`
}

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))

    api(project(":commission-settlement"))

    // REST 컨트롤러 얇게 (설계서 §10 Phase 12) — 얇은 컨트롤러 슬라이스.
    // 풀 부트 조립(실행 앱 + Oracle 프로파일)은 '운영 조립' Phase로 이연(§10 잔여).
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(testFixtures(project(":commission-rule")))
    testImplementation(testFixtures(project(":commission-calc")))
    testImplementation(testFixtures(project(":commission-limit")))
    testImplementation(testFixtures(project(":commission-deferral")))
}
