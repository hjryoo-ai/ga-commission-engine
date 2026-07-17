plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":commission-calc"))
    api(project(":commission-limit"))

    testFixturesApi(project(":commission-calc"))

    testImplementation(testFixtures(project(":commission-rule")))
    testImplementation(testFixtures(project(":commission-calc")))
    testImplementation(testFixtures(project(":commission-limit")))
}
