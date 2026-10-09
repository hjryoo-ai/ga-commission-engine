plugins {
    `java-library`
    `java-test-fixtures`
}

// 비교설명 등급·순위 산출 (Phase E3, ga-disclosure 계약 engine-disclosure.openapi.yaml).
// Spring 무의존 로직 + 포트. 정책(임계치·라벨·사유·동점 규칙)은 전부 데이터이며 코드에 상수가 없다.
// Oracle 어댑터는 commission-infra, 컨트롤러는 commission-api, 배선은 commission-app.
dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))

    api(project(":commission-rule"))                       // AmbiguousRuleException·RuleNotFoundException·RuleRepository
    api("com.fasterxml.jackson.core:jackson-databind")     // 정책 body(JSON) 해석·응답 조립
    implementation("io.github.erdtman:java-json-canonicalization:1.1")   // RFC 8785 JCS — 스냅샷 정규 바이트

    testFixturesApi(project(":commission-rule"))
    testFixturesApi(testFixtures(project(":commission-domain")))

    testImplementation(testFixtures(project(":commission-domain")))
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    testImplementation("com.networknt:json-schema-validator:1.5.9")
}

// 계약 파일(저장소 루트 contracts/)을 테스트가 읽는다. 경로만 넘기면 Gradle이 계약 변경을 모르고 시험을 UP-TO-DATE로 건너뛴다
// (E3.2 주입 J2에서 확인) — 디렉터리를 입력으로 선언한다.
tasks.withType<Test>().configureEach {
    systemProperty("engine.contractsDir", rootProject.file("contracts").absolutePath)
    inputs.dir(rootProject.file("contracts")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("engineContracts")
}
