plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":commission-calc"))
    api(project(":commission-limit"))
    api(project(":commission-clawback"))
    api(project(":commission-deferral"))

    // 골든 실행기(GoldenCase·GoldenEngine·GoldenComparator)는 시험 픽스처 — 아웃박스 시험이 같은 케이스를 발행 데코레이터로 돌린다(E1 C10)
    testFixturesApi(testFixtures(project(":commission-rule")))
    testFixturesApi(testFixtures(project(":commission-calc")))
    testFixturesApi(testFixtures(project(":commission-limit")))
    testFixturesApi(testFixtures(project(":commission-deferral")))

    testImplementation(testFixtures(project(":commission-rule")))
    testImplementation(testFixtures(project(":commission-calc")))
    testImplementation(testFixtures(project(":commission-limit")))
    testImplementation(testFixtures(project(":commission-clawback")))
    testImplementation(testFixtures(project(":commission-deferral")))
}
