// 순수 도메인 모듈: 어떤 프레임워크/라이브러리에도 의존하지 않는다.
// testFixtures: 시드 고정 속성 테스트 생성기(SeededCases) — jqwik 대체(Phase E3-0). 테스트 전용이며 main에는 영향 없음.
plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    testFixturesApi("org.junit.jupiter:junit-jupiter-params")
}
