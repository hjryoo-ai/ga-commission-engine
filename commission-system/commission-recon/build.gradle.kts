plugins {
    `java-library`
}

dependencies {
    api(project(":commission-domain"))
    api(project(":commission-inbound"))

    testImplementation(testFixtures(project(":commission-inbound")))
}
