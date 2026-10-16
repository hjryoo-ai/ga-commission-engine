plugins {
    `java-library`
    `java-test-fixtures`
}

// 아웃박스·이벤트 피드(Phase E1, 설계서 §6.8). Spring 무의존 — 이벤트 빌더·OutboxStore 포트·순번 부여기·저장소 데코레이터.
// 업무 모듈을 의존한다(outbox → settlement → calc/limit/deferral/clawback → rule → domain). 반대 방향은 Gradle 순환이라 불가(E1 Q4).
// Oracle 어댑터는 commission-infra, 피드 컨트롤러는 commission-api, 부여기·부트스트랩 잡은 commission-batch, 조립은 commission-app.
dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))

    api(project(":commission-settlement"))
    api("com.fasterxml.jackson.core:jackson-databind")     // payload 조립(정수 원·문자열만 — 소수는 문자열)

    // 포털 계약 검증기(시험 전용) — 포털 계약 사본(contracts/shared/)을 클래스패스에 담아 IRI를 저장소 사본으로만 푼다(원격 조회 0).
    testFixturesApi(project(":commission-settlement"))
    testFixturesApi("com.fasterxml.jackson.core:jackson-databind")
    testFixturesImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    testFixturesApi("com.networknt:json-schema-validator:1.5.9")

    testImplementation(testFixtures(project(":commission-rule")))
    testImplementation(testFixtures(project(":commission-calc")))
    testImplementation(testFixtures(project(":commission-limit")))
    testImplementation(testFixtures(project(":commission-clawback")))
    testImplementation(testFixtures(project(":commission-deferral")))
    testImplementation(testFixtures(project(":commission-settlement")))
}

// 계약 사본을 시험 픽스처 리소스로(contracts/shared/** → 클래스패스 contracts/shared/**). 리소스 처리가 입력을 추적하므로
// 사본이 바뀌면 이 모듈과 이것을 쓰는 시험이 다시 돈다.
tasks.named<ProcessResources>("processTestFixturesResources") {
    from(rootProject.file("contracts/shared")) {
        into("contracts/shared")
    }
}
