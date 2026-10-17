plugins {
    `java-library`
}

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))

    api(project(":commission-settlement"))
    api(project(":commission-disclosure"))   // 비교설명 등급·순위(Phase E3) — 컨트롤러가 서비스 위에 얇게
    api(project(":commission-outbox"))       // 이벤트 피드(Phase E1) — 컨트롤러가 EventFeed 위에 얇게

    // REST 컨트롤러 얇게 (설계서 §10 Phase 12) — 얇은 컨트롤러 슬라이스.
    // 풀 부트 조립(실행 앱 + Oracle 프로파일)은 '운영 조립' Phase로 이연(§10 잔여).
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(testFixtures(project(":commission-rule")))
    testImplementation(testFixtures(project(":commission-calc")))
    testImplementation(testFixtures(project(":commission-limit")))
    testImplementation(testFixtures(project(":commission-deferral")))
    testImplementation(testFixtures(project(":commission-disclosure")))
    testImplementation(testFixtures(project(":commission-outbox")))   // 피드 응답을 포털 FeedResponse 사본으로 검증(E1 C7)
    testImplementation(testFixtures(project(":commission-clawback")))  // 명세 조회 시험의 인메모리 엔진(E2 ③)
    testImplementation(testFixtures(project(":commission-settlement")))
}
