plugins {
    java
    application
}

// 로컬 스택 시나리오 실행기(E2 ⑤, 계획 P8) — 운영 jar 밖. 앱 조립(commission-app)을 그대로 쓰되 시계를 단계 시계로, 아웃박스 eventId를
// 순차로 바꿔 끼우고 A-1 두 번째 판(계산 → 마감 → 지급 → 해약 → 잠정 명세)을 실제 Oracle에 돌린 뒤 끝난다. 단계 시계·시나리오·순차 ID는
// commission-outbox testFixtures(운영 jar에 없다). 실행: localstack/up.sh (README "로컬 실행").
dependencies {
    implementation(project(":commission-app"))
    implementation(testFixtures(project(":commission-outbox")))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
}

application {
    mainClass.set("ga.comm.localstack.LocalStackScenario")
    applicationDefaultJvmArgs = listOf("-Duser.timezone=Asia/Seoul", "-Dfile.encoding=UTF-8")
}

// 작업 디렉터리 = commission-system(시드·상태 경로가 저장소 상대) — up.sh가 부른다.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    args("localstack/seed/a1.sql")
}

// 피드 대조(up.sh verify) — 두 번째 판 파일과 줄 단위 바이트 비교. 토큰은 파일 경로로만 받는다(값을 인자·로그에 두지 않는다).
tasks.register<JavaExec>("verifyFeed") {
    group = "verification"
    description = "로컬 스택 피드 = a1_portal_150000_e2.jsonl (바이트)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ga.comm.localstack.LocalStackVerify")
    workingDir = rootProject.projectDir
    systemProperty("engine.url", providers.gradleProperty("engineUrl").getOrElse("http://localhost:8080"))
    jvmArgs("-Duser.timezone=Asia/Seoul", "-Dfile.encoding=UTF-8")
    args(rootProject.file("commission-outbox/src/test/resources/outbox-golden/a1_portal_150000_e2.jsonl").absolutePath)
}
