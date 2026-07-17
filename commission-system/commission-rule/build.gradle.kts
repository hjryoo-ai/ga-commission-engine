plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":commission-domain"))

    testImplementation(testFixtures(project(":commission-rule")))
}
