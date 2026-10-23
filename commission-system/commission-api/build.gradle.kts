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

// 포털 시뮬레이션 샘플(포털 Phase 5 ⓪ — contracts/samples/simulations/): SimulationContractTest가 디렉터리를 바이트로 대조한다 —
// 입력으로 선언해야 샘플만 바뀌어도 시험이 다시 돈다. -Psamples.update=true 이면 그 시험이 파일을 다시 쓴다(포털에 사본 갱신을 알린다).
tasks.named<Test>("test") {
    val samples = rootProject.file("contracts/samples/simulations")
    systemProperty("engine.simSamplesDir", samples.absolutePath)
    inputs.files(rootProject.fileTree(samples)).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("simulationSamples")
    if (providers.gradleProperty("samples.update").orNull == "true") {
        systemProperty("samples.update", "true")
        outputs.upToDateWhen { false }
    }
}
