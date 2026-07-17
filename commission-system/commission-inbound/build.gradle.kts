plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":commission-domain"))

    testFixturesApi(project(":commission-domain"))
}
