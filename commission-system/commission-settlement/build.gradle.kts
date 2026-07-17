plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":commission-calc"))
    api(project(":commission-limit"))
    api(project(":commission-clawback"))
    api(project(":commission-deferral"))

    testImplementation(testFixtures(project(":commission-rule")))
    testImplementation(testFixtures(project(":commission-calc")))
    testImplementation(testFixtures(project(":commission-limit")))
    testImplementation(testFixtures(project(":commission-clawback")))
    testImplementation(testFixtures(project(":commission-deferral")))
}
