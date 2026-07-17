plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))

    api(project(":commission-domain"))

    // 시책 조건식 엔진(§3.6, Phase 13) — SpEL은 SimpleEvaluationContext(데이터 바인딩 전용)로만 쓴다.
    // implementation 범위로 캡슐화: SpEL 타입은 IncentiveConditionEvaluator 경계 밖으로 새지 않는다.
    implementation("org.springframework:spring-expression")

    testImplementation(testFixtures(project(":commission-rule")))
}
