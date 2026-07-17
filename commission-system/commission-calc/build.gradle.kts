plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":commission-domain"))
    api(project(":commission-rule"))

    testFixturesApi(project(":commission-domain"))
    testFixturesApi(project(":commission-rule"))
    testFixturesApi(testFixtures(project(":commission-rule")))

    testImplementation(testFixtures(project(":commission-rule")))
}
