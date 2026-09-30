plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":commission-calc"))

    testFixturesApi(project(":commission-calc"))

    testImplementation(testFixtures(project(":commission-rule")))
    testImplementation(testFixtures(project(":commission-calc")))
    testImplementation(testFixtures(project(":commission-domain")))   // SeededCases
}
